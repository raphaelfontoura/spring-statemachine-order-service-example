package com.github.raphaelfontoura.order_service;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;
import lombok.extern.java.Log;

@SpringBootApplication
public class OrderServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderServiceApplication.class, args);
	}

}

@Log
@Component
class Runner implements ApplicationRunner {

	private final OrderService service;
	private static final String ORDER_INFO = "order: ";

	Runner(OrderService service) {
		this.service = service;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {

		Order order = this.service.createOrder(LocalDate.now());
		log.info(String.format("After calling createOrder() -> %s", order.getOrderState()));
		log.info(ORDER_INFO + order);

		var paymentStateMachine = this.service.pay(order.getId(), UUID.randomUUID().toString());
		log.info(String.format("After calling pay() -> %s", paymentStateMachine.getState().getId().name()));
		log.info(ORDER_INFO + service.byId(order.getId()));

		var fulfillStateMachine = this.service.fulfill(order.getId());
		log.info(String.format("After calling fulfill() -> %s", fulfillStateMachine.getState().getId().name()));
		log.info(ORDER_INFO + service.byId(order.getId()));

	}

}
