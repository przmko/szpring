package org.example;

import org.example.demo.UserService;
import org.example.szpring.core.BeanDefinition;
import org.example.szpring.core.DefaultBeanNameGenerator;
import org.example.szpring.core.SimpleBeanDefinitionRegistry;

public class Main {
    public static void main(String[] args) {
        var registry = new SimpleBeanDefinitionRegistry();
        var nameGenerator = new DefaultBeanNameGenerator();

        Class<?> clazz = UserService.class;
        String name = nameGenerator.generateBeanName(clazz);
        registry.registerBeanDefinition(name, new BeanDefinition(clazz));

        BeanDefinition bd = registry.getBeanDefinition(name);
        System.out.println("Bean name   : " + name);
        System.out.println("Bean class  : " + bd.getBeanClass().getName());
        System.out.println("Scope       : " + bd.getScope());
        System.out.println("Primary     : " + bd.isPrimary());
    }
}
