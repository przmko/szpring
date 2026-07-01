# szpring — Build Your Own Spring DI Container

A hands-on guide to deeply understanding **Inversion of Control (IoC)**, **Dependency Injection (DI)**, and the **bean lifecycle** by reimplementing the core of the Spring container in pure Java (no external dependencies, just reflection).

> The goal is not feature-completeness with Spring. The goal is that by the end you can *explain* what Spring does under the hood, because you built a working slice of it yourself.

---

## How to use this guide

- Work through the milestones **in order**. Each one is independently runnable and builds on the previous.
- For each milestone: read **Concepts** first, then follow **Steps**, then confirm **Definition of Done** works before moving on.
- Don't peek at Spring's source until you've attempted a milestone — struggle first, then compare. Comparing your design to `DefaultListableBeanFactory` / `AbstractAutowireCapableBeanFactory` afterward is where a lot of the learning happens.
- Keep a scratch `Main` that wires up your container and prints what happened. Treat it as a live experiment log.

**Project facts**
- Java 26, Maven, base package `org.example`.
- Framework code lives under `org.example.szpring`.
- Beans you scan/test with live under a separate package, e.g. `org.example.demo`, so scanning has a realistic target.
- No dependencies needed. (Optional later: JUnit 5 for tests — see Appendix A.)

---

## The mental model (read this before coding)

**Inversion of Control**: normally *your* code decides when to create its collaborators (`new UserService(new UserRepo())`). With IoC, you hand that responsibility to a *container*. Your classes just declare what they need; the container decides how and when to build and connect them.

**Dependency Injection** is one way to achieve IoC: dependencies are *pushed into* an object (via constructor, setter, or field) rather than *pulled* by it.

**A bean** is any object the container manages: it creates it, injects its dependencies, runs lifecycle callbacks, and (for singletons) hands back the same instance every time.

**The container's job**, in phases:
1. **Discover** what classes should become beans (scanning / configuration).
2. **Describe** each as a `BeanDefinition` (metadata: class, scope, how to construct, what to inject).
3. **Instantiate** beans, resolving and injecting their dependencies (autowiring).
4. **Initialize** them (lifecycle callbacks, post-processors).
5. **Serve** them on request.
6. **Destroy** them on shutdown.

Spring separates two roles you'll mirror:
- **`BeanFactory`** — the minimal contract: "give me a bean by name/type."
- **`ApplicationContext`** — a richer facade: scanning, eager singleton creation, lifecycle management, wrapping a `BeanFactory` inside.

Keep that split in mind; we build the factory core first, then the context around it.

---

## Milestone 0 — Skeleton & annotations

**Goal:** package layout, the custom annotations you'll honor, and a smoke test.

**Concepts**
- Annotations are just metadata. The container is what gives them meaning by reading them via reflection. `@Component` does nothing on its own — *your scanner* is what makes it matter.
- Runtime-visible annotations require `@Retention(RUNTIME)`.

**Steps**
1. Create packages:
   - `org.example.szpring.annotation` — your annotations
   - `org.example.szpring.core` — factory/context/definitions
   - `org.example.szpring.exception` — custom exceptions
   - `org.example.demo` — sample beans to scan
2. Define these annotations (all `@Retention(RUNTIME)`; note the sensible `@Target`s):
   - `@Component` (TYPE) — mark a class as a managed bean; optional `String value()` for an explicit bean name.
   - `@Autowired` (CONSTRUCTOR, FIELD, METHOD) — mark an injection point; add `boolean required() default true`.
   - `@Qualifier` (FIELD, PARAMETER, TYPE) — `String value()`; disambiguate between candidates.
   - `@Primary` (TYPE) — preferred candidate when multiple match.
   - `@Scope` (TYPE) — `String value()`, e.g. `"singleton"` / `"prototype"`.
   - `@PostConstruct` / `@PreDestroy` (METHOD) — lifecycle hooks. (You may define your own instead of `jakarta.annotation.*` to avoid deps.)
   - `@Value` (FIELD, PARAMETER) — `String value()`; inject config/literals.
   - `@Configuration` (TYPE) + `@Bean` (METHOD) — Java-config factory beans (later milestone).
3. Define exceptions: `BeansException` (base, unchecked), `NoSuchBeanDefinitionException`, `NoUniqueBeanDefinitionException`, `BeanCreationException`, `CircularDependencyException`.

**Tips**
- Model the annotations after Spring's real signatures — it makes the eventual source comparison direct.
- Unchecked exceptions (extend `RuntimeException`) keep the API clean; Spring does the same.

**Definition of Done:** project compiles; you can annotate a demo class with `@Component` even though nothing reads it yet.

---

## Milestone 1 — BeanDefinition & the registry

**Goal:** the metadata model and where definitions live. No instantiation yet.

**Concepts**
- **Separate the *recipe* from the *dish*.** A `BeanDefinition` is the recipe (class, scope, constructor to use, injection points). The bean instance is the dish. Spring keeps these strictly separate — this separation is what enables scopes, lazy init, proxies, and post-processing.
- The **registry** is a `Map<String, BeanDefinition>` — bean name → recipe.

**Steps**
1. Create `BeanDefinition` holding: `Class<?> beanClass`, `String scope` (default `"singleton"`), `boolean primary`, and (fill in later) the resolved constructor, init/destroy method handles, and injection metadata. Start minimal; grow it per milestone.
2. Create a `BeanDefinitionRegistry` interface: `registerBeanDefinition(String name, BeanDefinition)`, `getBeanDefinition(String)`, `containsBeanDefinition(String)`, `String[] getBeanDefinitionNames()`.
3. Decide your **bean naming strategy**: explicit `@Component("name")` wins; otherwise decapitalize the simple class name (`UserService` → `userService`). Extract this into a `BeanNameGenerator` so it's swappable.

**Tips**
- Don't over-model `BeanDefinition` now. You'll be tempted to add every field up front — resist. Add fields the moment a milestone needs them, so each field has a clear reason to exist.
- Keep the registry a plain `HashMap` for now; note that Spring also tracks insertion order (`beanDefinitionNames` list) for deterministic iteration — mirror that with a `List<String>` alongside the map.

**Definition of Done:** you can manually register a `BeanDefinition` and read it back by name.

---

## Milestone 2 — Component scanning

**Goal:** discover `@Component` classes on the classpath under a base package and turn them into `BeanDefinition`s.

**Concepts**
- **Scanning = classpath enumeration + reflection filtering.** You convert a package name to a filesystem/JAR path, list `.class` files, load each class, and check for `@Component`.
- This is the "Discover" phase. Spring uses ASM to read bytecode without loading classes (faster, avoids side effects); you'll use the simpler `Class.forName` approach and note the tradeoff.

**Steps**
1. Write `ClassPathScanner#scan(String basePackage)` returning `Set<Class<?>>`:
   - Convert package `org.example.demo` → path `org/example/demo`.
   - Use `Thread.currentThread().getContextClassLoader().getResources(path)` to get URLs.
   - For `file:` URLs, walk the directory recursively collecting `*.class`; strip `.class`, convert `/`→`.`, `Class.forName(name, false, loader)`.
   - Filter to classes annotated with `@Component` (and later, meta-annotations).
2. Feed each discovered class into a `BeanDefinition` and register it (reuse Milestone 1).
3. Handle the JAR case too (`jar:` URL → `JarFile` entries) — or explicitly document it as a known limitation for now.

**Tips**
- Skip interfaces, abstract classes, and annotations themselves when scanning for concrete beans.
- Context classloader vs the class's own classloader matters in real apps; note it, use context classloader.
- **Trap:** loading a class runs its static initializers. Spring avoids this with ASM precisely to keep scanning side-effect-free. Acknowledge this rather than solving it now.

**Definition of Done:** `scan("org.example.demo")` finds your `@Component` classes and registers a definition per class with the right name.

---

## Milestone 3 — Instantiation & the singleton cache

**Goal:** actually create beans (no-arg constructor only) and cache singletons.

**Concepts**
- **The singleton cache** is a `Map<String, Object>` (name → instance). "Singleton" in Spring means *one per container*, not the GoF singleton pattern.
- **`getBean` is the heart of the factory.** On request: return cached instance if present; else create, cache, return.
- **Eager vs lazy:** an `ApplicationContext` eagerly instantiates all non-lazy singletons at startup (so misconfiguration fails fast). A bare `BeanFactory` is lazy.

**Steps**
1. Create `DefaultBeanFactory implements BeanFactory, BeanDefinitionRegistry` with a `singletonObjects` map.
2. Implement `Object getBean(String name)`:
   - If in `singletonObjects`, return it.
   - Else look up the `BeanDefinition`, instantiate via no-arg constructor (`getDeclaredConstructor().newInstance()`, `setAccessible(true)`), store in cache (singleton scope), return.
3. Implement `<T> T getBean(Class<T> type)` — resolve name(s) by type (see Milestone 5 for multiple-candidate rules), then delegate.
4. Create `AnnotationApplicationContext(String basePackage)` that: scans → registers definitions → eagerly instantiates singletons. Expose `getBean`.

**Tips**
- Wrap reflection failures in `BeanCreationException` with the bean name — good error messages are half of what makes Spring pleasant.
- Add a `refresh()` method on the context that runs the whole startup sequence. Spring's `refresh()` is the canonical lifecycle entry point; naming it the same pays off later.

**Definition of Done:** `context.getBean(SomeComponent.class)` returns a non-null instance, and two calls return the **same** instance.

---

## Milestone 4 — Constructor injection & dependency resolution

**Goal:** beans whose constructors take other beans get them injected automatically.

**Concepts**
- **Autowiring by constructor.** Pick a constructor, resolve each parameter to a bean, invoke it. This forces you to build dependencies *before* dependents — a topological ordering that emerges naturally from recursion.
- **Constructor selection rule (Spring's):** if exactly one constructor, use it. If multiple, use the one annotated `@Autowired`. If a no-arg exists and none is annotated, use it.

**Steps**
1. During definition building (Milestone 1/2), resolve and store the chosen constructor on the `BeanDefinition`.
2. In `createBean`: for each constructor parameter, call `getBean(paramType)` (recursion!) to resolve the argument, then `constructor.newInstance(args)`.
3. Let recursion handle ordering: requesting bean A triggers creation of its dependency B, which triggers C, etc.

**Tips**
- This is where **circular dependencies** first bite (A needs B, B needs A → infinite recursion / `StackOverflowError`). Don't solve it yet — instead add a `Set<String> beansCurrentlyInCreation` guard and throw a clear `CircularDependencyException` when you re-enter a bean already being created. You'll properly *resolve* the cycle in Milestone 9.
- Constructor injection is the injection style Spring recommends precisely *because* it makes cycles explicit and enables `final` fields.

**Definition of Done:** a `@Component` service with a constructor dependency on a `@Component` repository gets a fully-wired instance; a deliberate constructor cycle throws your clear exception (not `StackOverflowError`).

---

## Milestone 5 — Field/setter injection, @Qualifier, @Primary

**Goal:** support `@Autowired` on fields and setters, and resolve ambiguity between multiple candidates.

**Concepts**
- **Injection points** are places the container writes dependencies: constructor params, fields, setters. Field injection happens *after* construction — the object exists, then the container populates it.
- **Candidate resolution by type:** find all beans assignable to the required type. Zero → error (or skip if `required=false`). One → use it. Many → disambiguate.
- **Disambiguation order:** `@Qualifier("name")` match → `@Primary` bean → parameter/field name matches a bean name → else `NoUniqueBeanDefinitionException`.

**Steps**
1. After instantiation (the "populate" phase), scan the bean's class hierarchy for `@Autowired` fields and setter methods.
2. For each, resolve the value by type (honoring `@Qualifier`/`@Primary`) and set it (`field.setAccessible(true); field.set(bean, value)` / invoke setter).
3. Implement `resolveDependency(type, qualifier)` encapsulating the disambiguation rules. Reuse it for constructor params too.
4. Honor `required=false`: if no candidate, leave null instead of throwing.

**Tips**
- Walk `getDeclaredFields()` up the superclass chain — inherited injection points are real.
- Field injection is convenient but is what *allows* many circular dependencies to exist quietly; note the tradeoff vs constructor injection.
- Collection injection is a nice stretch: `@Autowired List<Handler> handlers` → inject *all* beans of that type. Add it once single-value works.

**Definition of Done:** field and setter `@Autowired` both work; with two beans of one interface, `@Primary` and `@Qualifier` each correctly pick the intended one, and having neither throws `NoUniqueBeanDefinitionException`.

---

## Milestone 6 — Scopes

**Goal:** support `singleton` (one per container) and `prototype` (new instance every `getBean`).

**Concepts**
- **Scope governs instance lifetime & caching.** Singletons are cached; prototypes are not — the container builds a fresh one each request and, importantly, **does not manage a prototype's destruction**.

**Steps**
1. Read `@Scope` when building the definition; default `singleton`.
2. In `getBean`: for `prototype`, always run `createBean` and *don't* cache. For `singleton`, use the cache.
3. Ensure eager instantiation at startup only applies to singletons.

**Tips**
- The classic gotcha: a **prototype injected into a singleton** is resolved *once* (at the singleton's creation) — so it behaves like a singleton thereafter. Reproduce this, understand why, and note how Spring solves it (method injection / `ObjectFactory` / scoped proxy). Implementing a real fix is optional but illuminating.

**Definition of Done:** two `getBean` calls on a prototype return **different** instances; on a singleton, the **same** instance.

---

## Milestone 7 — The bean lifecycle & callbacks

**Goal:** run initialization and destruction callbacks in the correct order.

**Concepts** — the full singleton creation lifecycle (memorize this; it's the crux of "understanding Spring"):
1. **Instantiate** (constructor).
2. **Populate** properties (field/setter injection).
3. **Aware** callbacks (e.g. `BeanNameAware#setBeanName`, `BeanFactoryAware#setBeanFactory`) — bean learns about the container.
4. **`BeanPostProcessor#postProcessBeforeInitialization`** (Milestone 8).
5. **Initialization**: `@PostConstruct` → `InitializingBean#afterPropertiesSet` → custom init method.
6. **`BeanPostProcessor#postProcessAfterInitialization`** (Milestone 8) — where proxies get created.
7. Bean is **ready / in use**.
8. On shutdown: `@PreDestroy` → `DisposableBean#destroy` → custom destroy method (singletons only, reverse creation order).

**Steps**
1. Define `Aware` marker interface plus `BeanNameAware`, `BeanFactoryAware`. Invoke them right after populate.
2. Detect and invoke `@PostConstruct` methods; support an `InitializingBean` interface too.
3. Track singletons for shutdown in a **disposable registry** (a list preserving creation order). Implement `context.close()` that invokes `@PreDestroy` / `DisposableBean#destroy` in **reverse** order.
4. Register a JVM shutdown hook (optional) or require explicit `close()` (implement `AutoCloseable` on the context so try-with-resources works).

**Tips**
- Cache resolved lifecycle methods on the `BeanDefinition` (reflection lookup is not free, and it clarifies the model).
- Reverse-order destruction matters: a bean may depend on another during teardown, so dependents die before their dependencies.
- Prototypes get init callbacks but **not** destroy callbacks — the container forgets them after handing them out. Make this explicit in code and a comment.

**Definition of Done:** a bean logs its callbacks and you observe the exact order above; `context.close()` fires destroy callbacks in reverse creation order for singletons only.

---

## Milestone 8 — BeanPostProcessors

**Goal:** the extension point that makes Spring *Spring* — hooks that can inspect or replace every bean during initialization.

**Concepts**
- A **`BeanPostProcessor`** has `postProcessBeforeInitialization(bean, name)` and `postProcessAfterInitialization(bean, name)`. Both can *return a different object*, replacing the bean. This is how `@Transactional`, AOP, `@Async`, and validation are implemented — they wrap beans in proxies in the *after* hook.
- BPPs are themselves beans. The container must find them, instantiate them **first**, then apply them to all other beans.

**Steps**
1. Define the `BeanPostProcessor` interface (both methods `default` returning the bean unchanged).
2. During `refresh()`: after registering definitions, instantiate all beans implementing `BeanPostProcessor` and collect them into a list.
3. In `createBean`, wrap the initialization phase: run all `before` processors → init callbacks → all `after` processors. Use the *returned* object downstream (it may be a proxy).
4. Prove the mechanism: write a logging BPP that prints each bean name, and one that wraps a marked bean in a `java.lang.reflect.Proxy` to log method calls.

**Tips**
- **Ordering trap:** BPPs must be created before the beans they process. Don't let a normal bean sneak in ahead. Spring adds `PriorityOrdered`/`Ordered`; a simple ordered list is enough here.
- Autowiring itself can be modeled as a BPP (Spring's `AutowiredAnnotationBeanPostProcessor`). Consider refactoring Milestone 5's injection into a BPP once this works — it's a satisfying "oh, *that's* why it's designed this way" moment.

**Definition of Done:** a custom BPP observes every bean during init, and a proxy-returning BPP successfully replaces a bean such that `getBean` returns the proxy.

---

## Milestone 9 — Circular dependency resolution (the three-level cache)

**Goal:** resolve `A ↔ B` cycles for **field/setter-injected singletons** the way Spring does.

**Concepts** — Spring's **three-level cache**:
- `singletonObjects` (L1): fully initialized beans.
- `earlySingletonObjects` (L2): instantiated-but-not-yet-populated beans exposed early.
- `singletonFactories` (L3): `ObjectFactory`s that can produce an early reference (and, crucially, the *proxy* if a BPP needs to wrap it).

**Why it works:** create A → instantiate (constructor done) → expose an early reference of A via L3 *before* populating A → populate A needs B → create B → B needs A → B finds A's early reference in the cache instead of recursing → B finishes → A finishes populating with the completed B. The cycle is broken because the *reference* to A exists before A is fully done.

**Why constructor cycles still can't be resolved:** the early reference only exists *after* the constructor runs. A constructor cycle needs A before A's constructor completes — impossible. So constructor cycles must error (Milestone 4's guard). Confirm you understand this asymmetry.

**Steps**
1. Add the three maps. When creating a singleton, after instantiation but before populate, register an `ObjectFactory` in L3 keyed by name.
2. In dependency resolution (`getBean`), check L1 → L2 → L3 before creating. If found in L3, invoke the factory, move the result to L2, return it.
3. The L3 factory must run any BPPs that would create early proxies, so the reference handed out matches the final object identity.
4. Keep the `beansCurrentlyInCreation` set to detect the *unresolvable* constructor cycles and error clearly.

**Tips**
- Test all four cases: field-cycle (resolves), constructor-cycle (errors clearly), self-reference, and a 3-node cycle A→B→C→A.
- This is the single most-asked Spring interview topic and the hardest milestone. Draw the sequence on paper before coding. If it works, you genuinely understand Spring's container.

**Definition of Done:** two mutually field-`@Autowired` singletons wire up successfully and `a.getB().getA() == a`; the constructor-cycle case throws your explicit exception.

---

## Milestone 10 — Java configuration: @Configuration & @Bean

**Goal:** register beans from factory methods, not just scanned classes.

**Concepts**
- A `@Configuration` class has `@Bean` methods; each method's *return value* is a bean, and the method name is the bean name. This handles third-party classes you can't annotate.
- Method parameters are injected like constructor params (autowiring by type).
- **Inter-bean references:** in real Spring, calling one `@Bean` method from another returns the *cached singleton*, not a new object — achieved by CGLIB-subclassing the config class. Implement the naive version first (direct call = new instance), then optionally add the proxy to see why Spring needs bytecode enhancement.

**Steps**
1. Scan also detects `@Configuration` classes. For each, create the config instance and register a `BeanDefinition` per `@Bean` method (store the method + declaring config bean as the factory).
2. In `createBean`, if a definition is factory-method-based, resolve the method's parameters via `getBean`, invoke the method on the config instance, use the return value as the bean.
3. Honor `@Scope`, init/destroy attributes on `@Bean` methods.

**Tips**
- Now you have *two* creation strategies (constructor vs factory method). A clean way to model this: a `BeanInstantiationStrategy` abstraction, or a nullable `factoryMethod` on `BeanDefinition` that `createBean` branches on.
- Note precisely why Spring proxies `@Configuration` classes — it's a great illustration of the recipe/instance separation.

**Definition of Done:** a `@Bean`-defined object (e.g. wrapping a JDK class) is retrievable via `getBean` and can be injected into scanned components.

---

## Milestone 11 — @Value & property resolution

**Goal:** inject external configuration and literals into beans.

**Concepts**
- `@Value("${db.url}")` pulls from a property source; `@Value("literal")` injects a constant. A **`PropertySource`/`Environment`** abstraction resolves `${...}` placeholders.
- Type conversion: the raw string must be converted to the target type (`int`, `boolean`, etc.) — the seed of Spring's `ConversionService`.

**Steps**
1. Load a `application.properties` from the classpath into a `Map<String,String>` behind an `Environment`.
2. During populate, resolve `@Value` on fields/params: parse `${key}` (with optional `:default`), look up, convert to the field type, inject.
3. Implement a minimal converter for primitives/wrappers/`String`.

**Tips**
- Keep placeholder resolution separate from conversion — two responsibilities, two classes. That separation is exactly why Spring has both `PropertySourcesPlaceholderConfigurer` and `ConversionService`.

**Definition of Done:** a bean receives an `int` and a `String` from `application.properties`, and a `${missing:fallback}` default resolves.

---

## Milestone 12 — Bonus: proxy-based AOP

**Goal:** tie it together — implement a tiny `@Transactional`-style aspect using a BPP + dynamic proxy.

**Concepts**
- AOP = wrap a bean in a proxy that runs *advice* around method calls. JDK dynamic proxies work when the bean implements an interface; CGLIB subclassing is needed otherwise (note this; JDK proxies are enough to learn the concept).
- The proxy is installed in `postProcessAfterInitialization` — connecting Milestones 8 and 9 (the early-reference/proxy identity concern).

**Steps**
1. Define `@Transactional` (or `@Logged`) and a BPP that, for beans with the annotation, returns a `Proxy.newProxyInstance` whose handler prints "begin/commit" around each call.
2. Verify the proxied bean still injects everywhere and lifecycle still fires on the *target*.

**Definition of Done:** calling a method on the retrieved bean shows advice running around it, and the container still manages the underlying bean's lifecycle.

---

## Suggested build order (dependency graph)

```
0 Skeleton
1 BeanDefinition/Registry
2 Scanning ──────────────┐
3 Instantiate+Singleton  │
4 Constructor injection   │
5 Field/setter + qualifiers
6 Scopes
7 Lifecycle callbacks
8 BeanPostProcessors ─────┐  (revisit 5 as a BPP)
9 Circular deps (3-cache) │  (needs 8 for proxy identity)
10 @Configuration/@Bean
11 @Value/Environment
12 AOP (needs 8)
```

Milestones 0–7 give you a *working* container. 8–9 give you the *real* Spring insight. 10–12 round it out.

---

## Concepts checklist — can you explain each without notes?

- [ ] IoC vs DI — the difference, and why DI is one form of IoC.
- [ ] `BeanFactory` vs `ApplicationContext` — responsibilities of each.
- [ ] Recipe vs instance — why `BeanDefinition` is separate from the bean.
- [ ] The full singleton lifecycle, in order (instantiate → populate → aware → BPP-before → init → BPP-after → use → destroy).
- [ ] Constructor vs field vs setter injection — tradeoffs, and which enables cycle resolution.
- [ ] Candidate disambiguation order (`@Qualifier` → `@Primary` → by-name).
- [ ] Singleton vs prototype — lifetime, caching, who destroys them, the prototype-in-singleton trap.
- [ ] What a `BeanPostProcessor` is and why AOP/transactions are built on it.
- [ ] The three-level cache — what each level holds and *why three, not two* (the proxy-identity reason).
- [ ] Why constructor cycles are unresolvable but field cycles are.
- [ ] Why Spring proxies `@Configuration` classes.

---

## Appendix A — Testing setup (optional but recommended)

Add JUnit 5 to `pom.xml` and write a test per milestone (same-instance for singletons, different-instance for prototypes, cycle resolution, callback ordering via a recording bean). Tests are the cleanest way to pin down "Definition of Done" and let you refactor fearlessly between milestones.

## Appendix B — Comparing to real Spring (do this after each milestone)

Map your classes to Spring's to cement the mental model:
- your `DefaultBeanFactory` ≈ `DefaultListableBeanFactory`
- your `createBean` ≈ `AbstractAutowireCapableBeanFactory#doCreateBean`
- your three-level cache ≈ `DefaultSingletonBeanRegistry`
- your scanner ≈ `ClassPathBeanDefinitionScanner`
- your context ≈ `AnnotationConfigApplicationContext`
- your `refresh()` ≈ `AbstractApplicationContext#refresh`

Read the corresponding Spring method *after* you've built yours — you'll recognize the shape, and the extra complexity (ordering, `@DependsOn`, lazy proxies, `FactoryBean`, meta-annotations) will read as "features on top of the core I already understand."

## Appendix C — Common traps (collected)

- Loading classes during scanning runs static initializers (Spring uses ASM to avoid this).
- BeanPostProcessors must be instantiated before the beans they process.
- Prototype injected into singleton is resolved once (behaves singleton-like).
- Prototypes never get destroy callbacks.
- Constructor cycles are fundamentally unresolvable — must error, not hang.
- Reflection injection needs `setAccessible(true)`; walk the superclass chain for inherited injection points.
- Destroy callbacks run in reverse creation order.
- Early proxy identity: the reference exposed in the L3 cache must be the same object BPPs will ultimately return.
