# Contributing to Polybot

Thank you for your interest in contributing to Polybot! This document provides guidelines and information for contributors.

## Ways to Contribute

### Report Bugs
- Use GitHub Issues to report bugs
- Include steps to reproduce, expected vs actual behavior
- Include relevant logs and configuration (sanitized of secrets)

### Suggest Features
- Open a GitHub Issue with the `enhancement` label
- Describe the use case and proposed solution
- Discuss trade-offs and alternatives considered

### Submit Code
- Fork the repository
- Create a feature branch
- Submit a Pull Request

## Development Setup

### Prerequisites
- Java 21+ (we use records, pattern matching, etc.)
- Maven 3.8+
- Docker & Docker Compose
- Python 3.11+ (for research tools)

### Building

```bash
# Build all modules
mvn clean package

# Run tests
mvn test

# Skip tests for faster builds
mvn clean package -DskipTests
```

### Running Locally

```bash
# Start infrastructure
docker-compose -f docker-compose.analytics.yaml up -d

# Run services in develop profile (paper trading)
cd executor-service && mvn spring-boot:run -Dspring-boot.run.profiles=develop
```

## Code Style

### Java
- Follow standard Java conventions
- Use meaningful variable and method names
- Keep methods focused and small
- Prefer immutable objects (records) where possible
- Use `@Slf4j` for logging

### Python
- Follow PEP 8
- Use type hints where practical
- Document functions with docstrings

### Commits
- Write clear, descriptive commit messages
- Use present tense ("Add feature" not "Added feature")
- Reference issues when applicable

## Architecture Guidelines

### Adding a New Strategy

1. Create a new class in `strategy-service/src/main/java/.../strategy/`
2. Implement the strategy loop (see `GabagoolDirectionalEngine` as reference)
3. Add configuration properties
4. Register the strategy bean
5. Add tests
6. Document in `docs/`

### Adding New Market Types

1. Update market discovery in `GabagoolMarketDiscovery`
2. Add slug patterns and timing rules
3. Update sizing schedules if applicable
4. Test with paper trading first

### Research Tools

1. Add Python scripts in `research/`
2. Include clear docstrings and usage examples
3. Use shared utilities from existing scripts
4. Update `requirements.txt` if adding dependencies

## Pull Request Process

1. **Branch Naming**: Use descriptive names like `feature/new-strategy` or `fix/order-timeout`

2. **PR Description**: Include:
   - What changes were made
   - Why the changes were necessary
   - How to test the changes
   - Any breaking changes

3. **Testing**:
   - Run `mvn test` and ensure all tests pass
   - Test manually with paper trading
   - For strategies, include backtesting results if applicable

4. **Review**: PRs require at least one approving review

5. **Merge**: Squash and merge to keep history clean

## Security

### Never Commit
- Private keys or wallet credentials
- API keys or secrets
- Real trading data with PII
- Hardcoded user-specific information

### Best Practices
- Use environment variables for all secrets
- Add sensitive patterns to `.gitignore`
- Review diffs before committing

## Testing Strategy Changes

Before submitting strategy changes:

1. **Backtest**: Run against historical data
2. **Paper Trade**: Test with simulated execution
3. **Compare**: Use research tools to compare against targets
4. **Document**: Include performance metrics in PR

## Questions?

- Open a GitHub Discussion for general questions
- Check existing Issues for similar questions
- Review the documentation in `docs/`

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
