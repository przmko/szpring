package org.example.szpring.exception;

public class NoUniqueBeanDefinitionException extends BeansException {
    public NoUniqueBeanDefinitionException(Class<?> beanType, int count) {
        super("Expected single matching bean of type '" + beanType.getName() +
              "' but found " + count);
    }
}
