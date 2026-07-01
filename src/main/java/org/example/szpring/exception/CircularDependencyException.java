package org.example.szpring.exception;

public class CircularDependencyException extends BeansException {
    public CircularDependencyException(String beanName) {
        super("Circular dependency detected while creating bean '" + beanName + "'");
    }
}
