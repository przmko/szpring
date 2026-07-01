package org.example.szpring.core;

import org.example.szpring.annotation.Component;

public class DefaultBeanNameGenerator implements BeanNameGenerator {

    @Override
    public String generateBeanName(Class<?> beanClass) {
        Component component = beanClass.getAnnotation(Component.class);
        if (component != null && !component.value().isEmpty()) {
            return component.value();
        }
        return decapitalize(beanClass.getSimpleName());
    }

    private String decapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
