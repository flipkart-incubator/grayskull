# Grayskull
[![Castle Grayskull](https://upload.wikimedia.org/wikipedia/en/a/ae/Castle_Grayskull.png)](https://en.wikipedia.org/wiki/Castle_Grayskull)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-green.svg)](https://spring.io/projects/spring-boot)
[![Build](https://github.com/flipkart-incubator/grayskull/actions/workflows/maven.yml/badge.svg)](https://github.com/flipkart-incubator/grayskull/actions/workflows/maven.yml)
[![Build](https://github.com/flipkart-incubator/grayskull/actions/workflows/clients-maven.yml/badge.svg)](https://github.com/flipkart-incubator/grayskull/actions/workflows/clients-maven.yml)
[![Coverage](https://codecov.io/github/flipkart-incubator/grayskull/graph/badge.svg)](https://codecov.io/gh/flipkart-incubator/grayskull)

**Grayskull** is an enterprise-grade secret management service designed for secure storage, retrieval, and lifecycle management of sensitive data like API keys, database credentials, and certificates.

## Features
- **Secure Secret Storage** - Encrypted storage with versioning support
- **Pluggable Architecture** - Extensible storage backends (WIP), authentication and authorization providers
- **Access Control** - Fine-grained authorization and audit logging

## Quick Start

### Prerequisites

- Java 21+
- MongoDB

### 1. Clone and Build

```bash
git clone https://github.com/flipkart-incubator/grayskull.git
cd grayskull
./mvnw clean install
```

### 2. Start the Server

```bash
./mvnw -f simple-app spring-boot:run
```

The server starts on <http://localhost:8080> with Swagger UI at `/swagger-ui.html`.

### 3. Use the Java Client

Add clojars repository to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>clojars</id>
        <url>https://repo.clojars.org</url>
    </repository>
</repositories>
```

Add dependency:

```xml
<dependency>
    <groupId>com.flipkart.grayskull</groupId>
    <artifactId>client-api</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>com.flipkart.grayskull</groupId>
    <artifactId>client-impl</artifactId>
    <version>0.1.0</version>
</dependency>
```

Basic usage:

```java
// Configure client
GrayskullClientConfiguration config = new GrayskullClientConfiguration();
config.setHost("http://localhost:8080");

// Create client with authentication
BasicAuthHeaderProvider auth = new BasicAuthHeaderProvider("user", "pass");
GrayskullClient client = new GrayskullClientImpl(auth, config);

// Retrieve secrets
SecretValue secret = client.getSecret("my-project:database-password");
String dbPassword = secret.getPrivatePart();
```
## Project Structure

- **Server** - `server` contains the core logic of Grayskull. It is not built to be standalone application but to be used as dependency in applications
- **SPI** - `server` needs some additional things which are pluggable. So SPI layer is used to provide interfaces for pluggable components
- **Client SDK** - Java library for seamless integration
- **Derby Audit System** - Provided an implementation of Async Audit SPI backed by Derby database
- **Simple App** - Since `server` is not a standalone application `simple-app` provided as a simple standalone application which just depends on server and derby-audit.

Both `server` and `derby-async-audit` depend on the SPI layer. `server` for using interfaces in SPI layer and `derby-async-audit` for providing one implementation in SPI layer.

```text

              +---------------+
              |               |
         +----+   simple-app  +------+
         |    |               |      |
         |    +---------------+      |
         |                           |
         |                           |
   +-----v------+           +--------v----+
   |            |           |             |
   |   server   |           | derby-audit |
   |            |           |             |
   +-----+------+           +--------+----+
         |                           |
         |                           |
         |                           |
         |     +-------------+       |
         |     |             |       |
         +----->     spi     <-------+
               |             |
               +-------------+

```
### Pluggable Components Architecture
Server has some components like Authentication Manager, Authorization Manager, Cryptography Manager depend on SPI interfaces for which different implementations can be plugged. Below is the diagram which shows how these managers and SPI are involved in normal HTTP request flow. `server` component provides simple implementations of all the SPIs.

```text
HTTP Request                                                                                   
     |                                                                     
     v                                                                     
+--------------------+     +------------------+                            
| Authentication     |     | Authentication   |                            
| Manager            |---->| SPI              |                            
+--------------------+     +------------------+                            
     |                                                                     
     v                                                                     
+--------------------+     +------------------+                            
| Authorization      |     | Authorization    |                            
| Manager            |---->| SPI              |                            
+--------------------+     +------------------+                            
     |                                                                     
     v                                                 +------------------+
+--------------------+      +--------------------+     | Cryptography     |
| Core Logic         |----->| Cryptography       |---->| SPI              |
+--------------------+      +--------------------+     +------------------+
     |                                                                     
     v                                                                     
+--------------------+     +------------------+                            
| Storage            |     | Repository       |                            
| Repositories       |---->| SPIs             |                            
+--------------------+     +------------------+                            
```

### Clients SDK
The Java SDK provides a robust, production-ready client with:

- **Automatic Retries** - Exponential backoff for transient failures
- **Connection Pooling** - Efficient HTTP connection management
- **Metrics Integration** - Micrometer and JMX support
- **Structured Logging** - MDC context for request tracing

Clients are provided as separate modules in the `clients` folder. For now only Java clients are provided, with support for Java 8 and higher.
```text
clients/              # Client libraries
└── java/             # Java SDK
    ├── client-api/   # Public interfaces
    └── client-impl/  # Implementation
```

See the [detailed client documentation](clients/java/README.md) for advanced usage.

## Configuration

### Using own implementation for authentication and authorization
Create a java application similar to `simple-app` and add the server's dependency in `pom.xml`.
```xml
<dependency>
    <groupId>com.flipkart.grayskull</groupId>
    <artifactId>server</artifactId>
    <version>0.1.0</version>
</dependency>
```
Optionally add own implementations of `GrayskullAuthenticationProvider` and `GrayskullAuthorizationProvider` in the application.  

For example if you want to override default authentication implementation with own implementation then add this in your application where spring boot can scan it.
```text
com.example.app
├── authentication
|   └── MyAuthenticationProvider.java
└── MyGrayskullApplication.java
```

```java
public class MyAuthenticationProvider implements GrayskullAuthenticationProvider {
    // authentication provider implementation
}
```
```java
@SpringBootApplication
public class MyGrayskullApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyGrayskullApplication.class, args);
    }
}
```
Now spring boot should recognize `MyAuthenticationProvider` as the default `GrayskullAuthenticationProvider` and should inject it wherever required.

---

**Note**
If your SPI implementations are in a separate dependency/package then you can add the required dependencies (order does not matter) in your application and add that package to `scanBasePackages` in the `@SpringBootApplication` annotation. don't forget to add the own package as well otherwise it will not get scanned.

Assuming `com.example.spi.impl` is the package of SPI implementations and `com.example.app` is the main application package then the annotation should something like this.
```java
@SpringBootApplication(scanBasePackages = {"com.example.spi.impl", "com.example.app"})
```
This way own SPI implementations are picked up first instead of simple implementations provided in `server` and used by the server.

---


## Development

### Building from Source

```bash
# Full build with tests
./mvnw clean install

# Skip tests for faster builds
./mvnw clean install -DskipTests

# Run specific module
./mvnw -f clients/java clean install
```

### Running Tests

```bash
# All tests
./mvnw clean test
```
This should also generate code coverage report in `target/site/jacoco` folder of all modules 

## Roadmap

- [x] Core secret storage and retrieval
- [x] Java client SDK with metrics
- [x] Pluggable authentication and authorization
- [x] Audit logging system
- [x] MongoDB storage backend
- [ ] Real-time secret update notifications
- [ ] Automated secret lifecycle management
- [ ] Enhanced metrics on secret usage
- [ ] Additional Golang SDKs
- [ ] Advanced lifecycle policies

## Contributing

### Development Workflow

1. Fork the repository
2. Create a feature branch (`git checkout -b feature`)
3. Commit your changes (`git commit -m 'Brief description about the feature'`)
4. Push to own fork (`git push fork feature`)
5. Open a Pull Request

### Code Standards

- Follow existing code style and patterns
- Run `./mvnw clean package` locally before pushing to GitHub
- Add tests for new functionality
- Update documentation as needed
- Ensure all CI checks pass

### Reporting Issues

- Use GitHub Issues for bug reports and feature requests
- Provide detailed reproduction steps for bugs
- Include relevant logs and configuration details

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

```text
Copyright 2025 Flipkart

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

**Built with ❤️ by the Flipkart Security Team**
