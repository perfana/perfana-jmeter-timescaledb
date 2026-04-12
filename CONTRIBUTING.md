# Contributing to Perfana JMeter TimescaleDB Backend Listener

## Building

```bash
# Build
./gradlew build

# Build fat JAR
./gradlew fatJar

# Publish to local Maven repository
./gradlew publishToMavenLocal
```

Requires Java 17+.

## Pull Requests

1. Fork the repository
2. Create a feature branch from `main`
3. Make your changes
4. Run `./gradlew build` to verify compilation
5. Submit a pull request

## Coding Standards

- Java 17
- Follow existing code style
- Use builder pattern for data model classes
- Prefer `compileOnly` scope for JMeter and logging dependencies

## Testing

Integration tests require a running TimescaleDB instance. Use Docker:

```bash
docker run -d --name timescaledb \
  -p 5432:5432 \
  -e POSTGRES_PASSWORD=password \
  -e POSTGRES_DB=jmeter \
  timescale/timescaledb:latest-pg16
```

## Release Process

1. Update version in `build.gradle`
2. Update `CHANGELOG.md`
3. Tag the release: `git tag v1.0.0`
4. Push the tag: `git push origin v1.0.0`
5. GitHub Actions publishes to Maven Central and creates a GitHub Release
