#!/bin/sh
set -eu
if [ "$(id -u)" = "0" ]; then
  chown context:context /var/lib/code-context /var/lib/code-context/repos /var/lib/code-context/zoekt /data /data/index 2>/dev/null || true
  exec gosu context:context java -XX:MaxRAMPercentage=70 -jar /app/app.jar
fi
exec java -XX:MaxRAMPercentage=70 -jar /app/app.jar
