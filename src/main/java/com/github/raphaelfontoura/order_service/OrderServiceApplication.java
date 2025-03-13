package com.github.raphaelfontoura.order_service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.stereotype.Component;

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

@Log
@Component
class Runner implements ApplicationRunner {

	private static final String ORDER_ID_KEY = "orderId";
	private final StateMachineFactory<OrderStates, OrderEvents> factory;

	Runner(StateMachineFactory<OrderStates, OrderEvents> factory) {
		this.factory = factory;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {

		StateMachine<OrderStates, OrderEvents> machine = this.factory.getStateMachine("12345");
		machine.startReactively().subscribe();
		
		log.info("Current state:" + machine.getState().getId().name());

		machine.sendEvent(Mono.just(messageBuild(OrderEvents.PAY, 1L))).subscribe();
		log.info("Current state: " + machine.getState().getId().name());

		machine.sendEvent(Mono.just(messageBuild(OrderEvents.FULFILL, 1L))).subscribe();
		log.info("Current state: " + machine.getState().getId().name());
		log.info(String.format("Order id: %s finished.", machine.getExtendedState().getVariables().get(ORDER_ID_KEY)));
	}

	private Message<OrderEvents> messageBuild(OrderEvents event, Long orderId) {
		return MessageBuilder.withPayload(event)
				.setHeader(ORDER_ID_KEY, orderId)
				.build();
	}

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
				.source(OrderStates.PAID).target(OrderStates.FULFILLED).action(processPayment()).event(OrderEvents.FULFILL)
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
