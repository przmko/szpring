package org.example.szpring.core;

public class BeanDefinition {

    private final Class<?> beanClass;
    private String scope = "singleton";
    private boolean primary = false;

    public BeanDefinition(Class<?> beanClass) {
        this.beanClass = beanClass;
    }

    public Class<?> getBeanClass() { return beanClass; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }

    public boolean isSingleton() { return "singleton".equals(scope); }
    public boolean isPrototype() { return "prototype".equals(scope); }
}
