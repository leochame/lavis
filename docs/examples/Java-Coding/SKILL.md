---
name: Java-Coding
description: Expert Java coding assistant with Spring Boot best practices
category: development
version: 1.0.0
author: Lavis Team
command: agent:{{task}}
parameters:
  - name: task
    description: The coding task to perform
    required: true
    type: string
  - name: style
    description: Code style preference
    default: clean
    type: string
    enum:
      - clean
      - verbose
      - minimal
---

# Java Coding Best Practices

When writing Java code, you MUST follow these guidelines:

## Code Style

1. **Naming Conventions**
   - Classes: PascalCase (e.g., `UserService`, `OrderController`)
   - Methods/Variables: camelCase (e.g., `getUserById`, `orderCount`)
   - Constants: UPPER_SNAKE_CASE (e.g., `MAX_RETRY_COUNT`)
   - Packages: lowercase (e.g., `com.example.service`)

2. **Class Structure**
   ```java
   public class ExampleService {
       // 1. Static fields
       private static final Logger log = LoggerFactory.getLogger(ExampleService.class);

       // 2. Instance fields
       private final DependencyA dependencyA;
       private final DependencyB dependencyB;

       // 3. Constructor (prefer constructor injection)
       public ExampleService(DependencyA dependencyA, DependencyB dependencyB) {
           this.dependencyA = dependencyA;
           this.dependencyB = dependencyB;
       }

       // 4. Public methods
       // 5. Private methods
   }
   ```

## Spring Boot Specifics

1. **Dependency Injection**
   - Always use constructor injection (not @Autowired on fields)
   - Use `@RequiredArgsConstructor` from Lombok for cleaner code

2. **REST Controllers**
   ```java
   @RestController
   @RequestMapping("/api/v1/users")
   @RequiredArgsConstructor
   public class UserController {
       private final UserService userService;

       @GetMapping("/{id}")
       public ResponseEntity<UserDto> getUser(@PathVariable Long id) {
           return userService.findById(id)
               .map(ResponseEntity::ok)
               .orElse(ResponseEntity.notFound().build());
       }
   }
   ```

3. **Service Layer**
   - Use `@Service` annotation
   - Keep business logic in services, not controllers
   - Use `@Transactional` appropriately

4. **Exception Handling**
   - Create custom exceptions extending `RuntimeException`
   - Use `@ControllerAdvice` for global exception handling

## Error Handling

1. **Never swallow exceptions silently**
   ```java
   // BAD
   try {
       doSomething();
   } catch (Exception e) {
       // empty
   }

   // GOOD
   try {
       doSomething();
   } catch (SpecificException e) {
       log.error("Failed to do something: {}", e.getMessage(), e);
       throw new ServiceException("Operation failed", e);
   }
   ```

2. **Use Optional instead of null**
   ```java
   // BAD
   public User findUser(Long id) {
       return userRepository.findById(id).orElse(null);
   }

   // GOOD
   public Optional<User> findUser(Long id) {
       return userRepository.findById(id);
   }
   ```

## Testing

1. **Unit Tests**
   - Use JUnit 5 with `@Test` annotation
   - Use Mockito for mocking dependencies
   - Follow AAA pattern: Arrange, Act, Assert

2. **Integration Tests**
   - Use `@SpringBootTest` for full context
   - Use `@WebMvcTest` for controller tests
   - Use `@DataJpaTest` for repository tests

## Documentation

1. **Javadoc for public APIs**
   ```java
   /**
    * Retrieves a user by their unique identifier.
    *
    * @param id the user's unique identifier
    * @return the user if found
    * @throws UserNotFoundException if no user exists with the given id
    */
   public User getUserById(Long id) { ... }
   ```

Remember: Clean code is more important than clever code. Prioritize readability and maintainability.
