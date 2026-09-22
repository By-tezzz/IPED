#!/bin/sh
#
# Starts the IPED web interface for one or more already processed cases.
#
#   ./iped-web.sh --case=/path/to/case [--case=/path/to/other-case] [--port=8080] [--host=0.0.0.0]
#
# The interface is then available at http://localhost:8080/app/ and the REST API
# it uses is documented at http://localhost:8080/swagger.json
#
# Cases can also be listed in a json file: --sources=/path/to/sources.json
#
# The server has no authentication, so use --host=127.0.0.1 unless it is on a
# trusted network or behind an authenticating reverse proxy.
#
DIR=$(cd "$(dirname "$0")" && pwd)

# Same JVM options the desktop application uses (see iped.app.bootstrap.Bootstrap).
# java.security.manager=allow is needed from Java 18 on, where IPED still installs
# the security manager that blocks internet access from viewers.
exec java -Xmx4G -Dfile.encoding=UTF-8 \
    -XX:+IgnoreUnrecognizedVMOptions \
    -XX:+HeapDumpOnOutOfMemoryError \
    -Djava.security.manager=allow \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.math=ALL-UNNAMED \
    --add-opens=java.base/java.net=ALL-UNNAMED \
    --add-opens=java.base/java.io=ALL-UNNAMED \
    --add-opens=java.base/java.nio=ALL-UNNAMED \
    --add-opens=java.base/java.text=ALL-UNNAMED \
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    -cp "$DIR/lib/*" iped.engine.webapi.Main "$@"
