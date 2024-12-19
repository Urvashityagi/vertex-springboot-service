package com.example.studentsystem.entities;

import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

@Entity
@Data
public class App {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    private String version;
    private boolean installed;

    // Getters and Setters
}
