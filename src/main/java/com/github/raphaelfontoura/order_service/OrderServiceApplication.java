package com.github.raphaelfontoura.order_service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.statemachine.support.StateMachineInterceptorAdapter;
import org.springframework.statemachine.transition.Transition;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class OrderServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderServiceApplication.class, args);
	}

}

enum OrderStates {
	SUBMITTED, PAID, FULFILLED, CANCELLED
}

enum OrderEvents {
	FULFILL, PAY, CANCEL
}

@Service
@Log
class OrderService {

	private final OrderRepository repository;
	private final StateMachineFactory<OrderStates, OrderEvents> factory;
	private static final String ORDER_ID_KEY = "orderId";

	OrderService(OrderRepository repository, StateMachineFactory<OrderStates, OrderEvents> factory) {
		this.factory = factory;
		this.repository = repository;
	}

	Order createOrder(LocalDate when) {
		return this.repository.save(new Order(when, OrderStates.SUBMITTED));
	}

	StateMachine<OrderStates, OrderEvents> fulfill(Long orderId) {
		var machine = this.build(orderId);
		var fullfillMessage = MessageBuilder.withPayload(OrderEvents.FULFILL)
				.setHeader(ORDER_ID_KEY, orderId)
				.build();
		machine.sendEvent(Mono.just(fullfillMessage)).subscribe(
				item -> {	
					item.getMessage().getHeaders().forEach((k, v) -> log.info(String.format("Key: %s, Value: %s", k, v)));
					log.info("Success");
				},
				error -> log.info("Error"),
				() -> log.info("Complete")
		);
		return machine;
	}

	StateMachine<OrderStates, OrderEvents> pay(Long orderId, String paymentConfirmationNumber) {
		var machine = this.build(orderId);
		var paymentMessage = MessageBuilder.withPayload(OrderEvents.PAY)
				.setHeader(ORDER_ID_KEY, orderId)
				.setHeader("paymentConfirmationNumber", paymentConfirmationNumber)
				.build();
		machine.sendEvent(Mono.just(paymentMessage)).subscribe(
			item -> {	
				item.getMessage().getHeaders().forEach((k, v) -> log.info(String.format("Key: %s, Value: %s", k, v)));
				log.info("Success");
			},
			error -> log.info("Error"),
			() -> log.info("Complete")
		);
		return machine;
	}

	private StateMachine<OrderStates, OrderEvents> build(Long orderId) {
		Order order = this.repository.findById(orderId).orElseThrow();
		String orderIdKey = Long.toString(order.getId());

		StateMachine<OrderStates, OrderEvents> sm = factory.getStateMachine(orderIdKey);

		sm.stopReactively().subscribe();

		sm.getStateMachineAccessor()
				.doWithAllRegions(sma -> {
					sma.addStateMachineInterceptor(new StateMachineInterceptorAdapter<OrderStates, OrderEvents>() {

						@Override
						public void preStateChange(State<OrderStates, OrderEvents> state, Message<OrderEvents> message,
								Transition<OrderStates, OrderEvents> transition,
								StateMachine<OrderStates, OrderEvents> stateMachine,
								StateMachine<OrderStates, OrderEvents> rootStateMachine) {
							Optional.ofNullable(message)
									.ifPresent(msg -> Optional
											.ofNullable(
													Long.class.cast(msg.getHeaders().getOrDefault(ORDER_ID_KEY, -1L)))
											.ifPresent(orderId -> {
												Order order = repository.findById(orderId).orElseThrow();
												order.setOrderState(state.getId());
												repository.save(order);
											}));
						}
					});
					sma.resetStateMachineReactively(
							new DefaultStateMachineContext<>(order.getOrderState(), null, null, null)).block();

				});

		sm.startReactively().subscribe();

		return sm;
	}

}

@Log
@Component
class Runner implements ApplicationRunner {

	private final OrderService service;

	Runner(OrderService service) {
		this.service = service;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {

		Order order = this.service.createOrder(LocalDate.now());
		log.info(String.format("After calling createOrder() -> %s", order.getOrderState()));

		var paymentStateMachine = this.service.pay(order.getId(), UUID.randomUUID().toString());
		log.info(String.format("After calling pay() -> %s", paymentStateMachine.getState().getId().name()));

		var fulfillStateMachine = this.service.fulfill(order.getId());
		log.info(String.format("After calling fulfill() -> %s", fulfillStateMachine.getState().getId().name()));

	}

}

@Entity(name = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
class Order {
	@Id
	@GeneratedValue
	private Long id;
	private LocalDate datetime;
	private String state;

	Order(LocalDate datetime, OrderStates state) {
		this.datetime = datetime;
		this.state = state.name();
	}

	public OrderStates getOrderState() {
		return OrderStates.valueOf(this.state);
	}

	public void setOrderState(OrderStates state) {
		this.state = state.name();
	}
}

interface OrderRepository extends JpaRepository<Order, Long> {
}

@Log
@Configuration
@EnableStateMachineFactory
class SimpleEnumStateMachineConfiguration extends StateMachineConfigurerAdapter<OrderStates, OrderEvents> {

	@Override
	public void configure(StateMachineConfigurationConfigurer<OrderStates, OrderEvents> config) throws Exception {

		StateMachineListenerAdapter<OrderStates, OrderEvents> listener = new StateMachineListenerAdapter<OrderStates, OrderEvents>() {
			@Override
			public void stateChanged(State<OrderStates, OrderEvents> from, State<OrderStates, OrderEvents> to) {
				if (from == null) {
					log.info(String.format("State changed to: %s", to.getId()));
				} else {
					log.info(String.format("State changed from: %s, to: %s", from.getId(), to.getId()));
				}
			}
		};

		config.withConfiguration()
				.autoStartup(false)
				.listener(listener);
	}

	@Override
	public void configure(StateMachineStateConfigurer<OrderStates, OrderEvents> states) throws Exception {
		states
				.withStates()
				.initial(OrderStates.SUBMITTED)
				.stateEntry(OrderStates.SUBMITTED, context -> {
					var orderId = (Long) context.getMessageHeader("orderId");
					log.info(String.format("Order id: %d submitted", orderId));
					context.getExtendedState().getVariables().put("orderId", orderId);
				})
				.state(OrderStates.PAID)
				.end(OrderStates.FULFILLED)
				.end(OrderStates.CANCELLED);
	}

	@Override
	public void configure(StateMachineTransitionConfigurer<OrderStates, OrderEvents> transitions) throws Exception {
		transitions
				.withExternal()
				.source(OrderStates.SUBMITTED).target(OrderStates.PAID).event(OrderEvents.PAY)
				.and()
				.withExternal()
				.source(OrderStates.PAID).target(OrderStates.FULFILLED).action(processPayment())
				.event(OrderEvents.FULFILL)
				.and()
				.withExternal()
				.source(OrderStates.SUBMITTED).target(OrderStates.CANCELLED).event(OrderEvents.CANCEL)
				.and()
				.withExternal()
				.source(OrderStates.PAID).target(OrderStates.CANCELLED).event(OrderEvents.CANCEL);
	}

	private Action<OrderStates, OrderEvents> processPayment() {
		return context -> {
			Long orderId = context.getMessage().getHeaders().get("orderId", Long.class);
			log.info(String.format("Processing payment for order id: %d", orderId));
			context.getExtendedState().getVariables().put("orderId", orderId);
		};
	}

}
