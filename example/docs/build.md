# How To Build

<br>

## Building Native Image Using GraalVM

With Graalvm, we can build the native executable binary using [native-maven-plugin](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html).

From the command line,

```bash
mvn clean native:compile
```

The executable is located at `target/vertx-native`. The name is configured by `imageName` property for the **native-maven-plugin** in the `pom.xml` file.


<br>

