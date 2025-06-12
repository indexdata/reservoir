# Run The Application


    
Assuming the built binary is located at `target/vertx-native`, from the same directory,

```bash
./target/vertx-native
```

Output:

```bash
2025-03-23T22:50:56.822887Z main INFO Starting configuration XmlConfiguration[location=resource:/log4j2.xml, lastModified=2025-03-23T20:21:13Z]...
2025-03-23T22:50:56.822987Z main INFO Start watching for changes to resource:/log4j2.xml every 0 seconds
2025-03-23T22:50:56.823013Z main INFO Configuration XmlConfiguration[location=resource:/log4j2.xml, lastModified=2025-03-23T20:21:13Z] started.
2025-03-23T22:50:56.823071Z main INFO Stopping configuration org.apache.logging.log4j.core.config.DefaultConfiguration@70e4169d...
2025-03-23T22:50:56.823098Z main INFO Configuration org.apache.logging.log4j.core.config.DefaultConfiguration@70e4169d stopped.
2025-03-23 15:50:56 WARN - main - DnsServerAddressStreamProviders:70 - Can not find io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider in the classpath, fallback to system defaults. This may result in incorrect DNS resolutions on MacOS. Check whether you have a dependency on 'io.netty:netty-resolver-dns-native-macos'
2025-03-23 15:50:56 INFO - vert.x-eventloop-thread-0 - MainVerticle:20 - HTTP server started on port 8888
2025-03-23 15:50:56 INFO - vert.x-eventloop-thread-1 - Main:14 - ✅ Started
```

<br>