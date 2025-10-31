# Reqeust Resource Profiler

A lightweight Java instrumentation agent built to collect profiling data such as thread lifecycle events, execution metrics, and contextual information. 
This agent is intended to be attached to JVM applications using the `-javaagent` flag.

---

## Features

* **Java Agent using Premain-Class**
* **Thread start advice instrumentation** via ByteBuddy
* **Profiling hooks** for method calls, thread events, and contextual metadata
* **JSON reporting** using Jackson Databind
* **Supports class redefinition and retransformation**

---

## Project Structure

```
resource-profiler/
├─ src/main/java/com/resource/profiler/
│  ├─ agent/              # Request Advicers
│  ├─ core/               # Core profiler logic
│  └─ Profiler.java       # Premain entry point + Agent Configuratrion
├─ src/main/resources/    # Manifest + config files - if any
├─ build.gradle           
└─ settings.gradle
```

---

## How It Works

The profiler is packaged as a **Java Agent**, which means it runs before the application starts.

When the JVM is launched with:

```
-javaagent:architect-1.0.jar
```

The JVM looks for the manifest entry:

```
Premain-Class: com.resource.profiler.Profiler
```

This class initializes Profiler and begins instrumentation.

---

## Requirements

* **Java 17+** (supports JDK 24 as used in your project)
* **Gradle 8+**
* Dependencies:

    * `ByteBuddy`
    * `Jackson Databind`

---

## Building the Profiler

To build the JAR:

```
./gradlew clean build
```

The output JAR will appear at:

```
build/libs/architect-1.0.jar
```

---

## Running With a Target Application

To attach this profiler to any JVM app:

```
java -javaagent:/path/to/architect-1.0.jar -jar your-app.jar
```

---

## Manifest Configuration

Your `build.gradle` must include:

```gradle
jar {
    manifest {
        attributes(
            'Premain-Class': 'com.resource.profiler.Profiler',
            'Agent-Class': 'com.resource.profiler.Profiler',
            'Can-Redefine-Classes': 'true',
            'Can-Retransform-Classes': 'true'
        )
    }
}
```

---

## Testing

This project uses JUnit Platform. Run tests using:

```
./gradlew test
```

---

## 📄 License

This project is proprietary unless otherwise specified.

---

## 🙋 Support

If you encounter issues, feel free to ask for help or open an issue in your repository.
