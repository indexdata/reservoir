# Vertx GraalVM Native Image

This is a project which uses GraalVM to build a native image of a super simple Vert.x application. Previously the [vertx-graal-native-image](https://github.com/cch0/vertx-graal-native-image)
project demonstrates building a native Vert.x application with Logback as logging implementation due to the reason Log4j has not been updated to support GraalVM.

Fast forward to March 2025, a snapshot version of Log4j (`2.25.0-SNAPSHOT`) has been provided and this allows us to continue using Log4j if this is your preferred logging implementation.

<br>

Also in this project we also provide Github pipeline to build native image for the following platforms and OSes

- Linux, x86_64
- MacOS, ARM64
- Windows, X86_64

It is also possible to include Linux Arm64 but this is not available for public repository at the moment.

After Github pipeline finishes building images for all configured platforms, the exectuable files are available for download when you visit the Summary page for the build job.


<br>

## How To Build

See [How To Build](./docs/build.md)

<br>

## How To Run The Application

See [How To Run](./docs/execution.md)

<br>



