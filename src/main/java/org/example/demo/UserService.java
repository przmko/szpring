package org.example.demo;

import org.example.szpring.annotation.Component;

@Component
public class UserService {

    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
