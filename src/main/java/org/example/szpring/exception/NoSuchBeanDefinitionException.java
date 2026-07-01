package org.example.szpring.exception;

public class NoSuchBeanDefinitionException extends BeansException {
    public NoSuchBeanDefinitionException(String beanName) {
        super("No bean named '" + beanName + "' available");
    }

    public NoSuchBeanDefinitionException(Class<?> beanType) {
        super("No qualifying bean of type '" + beanType.getName() + "' available");
    }
}
