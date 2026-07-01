package org.example.szpring.exception;

public class BeanCreationException extends BeansException {
    public BeanCreationException(String beanName, String message) {
        super("Error creating bean with name '" + beanName + "': " + message);
    }

    public BeanCreationException(String beanName, String message, Throwable cause) {
        super("Error creating bean with name '" + beanName + "': " + message, cause);
    }
}
