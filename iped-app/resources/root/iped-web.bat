@echo off
rem
rem Starts the IPED web interface for one or more already processed cases.
rem
rem   iped-web.bat --case=C:\path\to\case [--case=...] [--port=8080] [--host=0.0.0.0]
rem
rem The interface is then available at http://localhost:8080/app/ and the REST API
rem it uses is documented at http://localhost:8080/swagger.json
rem
rem Cases can also be listed in a json file: --sources=C:\path\to\sources.json
rem
rem The server has no authentication, so use --host=127.0.0.1 unless it is on a
rem trusted network or behind an authenticating reverse proxy.
rem
rem Same JVM options the desktop application uses (see iped.app.bootstrap.Bootstrap).
rem java.security.manager=allow is needed from Java 18 on, where IPED still installs
rem the security manager that blocks internet access from viewers.
java -Xmx4G -Dfile.encoding=UTF-8 ^
    -XX:+IgnoreUnrecognizedVMOptions ^
    -XX:+HeapDumpOnOutOfMemoryError ^
    -Djava.security.manager=allow ^
    --add-opens=java.base/java.util=ALL-UNNAMED ^
    --add-opens=java.base/java.util.concurrent=ALL-UNNAMED ^
    --add-opens=java.base/java.lang=ALL-UNNAMED ^
    --add-opens=java.base/java.math=ALL-UNNAMED ^
    --add-opens=java.base/java.net=ALL-UNNAMED ^
    --add-opens=java.base/java.io=ALL-UNNAMED ^
    --add-opens=java.base/java.nio=ALL-UNNAMED ^
    --add-opens=java.base/java.text=ALL-UNNAMED ^
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED ^
    -cp "%~dp0lib\*" iped.engine.webapi.Main %*
