package ru.app.draft.models;

import lombok.Data;

import java.util.Date;

@Data
public class Order {
    private Long price;

    public Order(Long price, Long quantity, String direction, Date date) {
        this.price = price;
        this.quantity = quantity;
        this.direction = direction;
        this.date = date;
    }

    private Long quantity;
    private String direction;
    private Date date;
}
