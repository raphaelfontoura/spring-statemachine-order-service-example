package com.github.raphaelfontoura.order_service;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.statemachine.support.StateMachineInterceptorAdapter;
import org.springframework.statemachine.transition.Transition;
import org.springframework.stereotype.Service;

import lombok.extern.java.Log;
import reactor.core.publisher.Mono;

@Service
@Log
public class OrderService {

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

	Order byId(Long orderId) {
		return this.repository.findById(orderId).orElseThrow();
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
