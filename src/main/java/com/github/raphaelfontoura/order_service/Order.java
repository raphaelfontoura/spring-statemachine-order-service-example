package com.github.raphaelfontoura.order_service;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
