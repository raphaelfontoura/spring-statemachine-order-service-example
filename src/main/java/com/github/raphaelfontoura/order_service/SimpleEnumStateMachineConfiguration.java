package com.github.raphaelfontoura.order_service;

import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;

import lombok.extern.java.Log;

@Log
@Configuration
@EnableStateMachineFactory
class SimpleEnumStateMachineConfiguration extends StateMachineConfigurerAdapter<OrderStates, OrderEvents> {

    private static final String ORDER_ID_KEY = "orderId";

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
					var orderId = (Long) context.getMessageHeader(ORDER_ID_KEY);
					log.info(String.format("Order id: %d submitted", orderId));
					context.getExtendedState().getVariables().put(ORDER_ID_KEY, orderId);
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
			Long orderId = context.getMessage().getHeaders().get(ORDER_ID_KEY, Long.class);
			log.info(String.format("Processing payment for order id: %d", orderId));
			context.getExtendedState().getVariables().put(ORDER_ID_KEY, orderId);
		};
	}

}
