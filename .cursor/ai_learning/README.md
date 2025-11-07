# AI Learning Database

This directory contains the learning database for the AI Build Fixer agent.

## Files

- **`patterns.json`**: Error pattern database with fixes and confidence scores
- **`knowledge.md`**: Knowledge base with error classifications and fix strategies
- **`README.md`**: This file

## How It Works

1. **Pattern Matching**: When a build fails, the agent matches error logs against patterns in `patterns.json`
2. **Fix Application**: Based on the matched pattern, the agent applies the corresponding fix
3. **Learning**: After successful fixes, patterns are updated with success rates
4. **Evolution**: New patterns are added as new error types are discovered

## Pattern Format

Each pattern in `patterns.json` contains:
- `error`: Regex pattern to match error messages
- `fix`: Description of the fix to apply
- `context`: Category (cmake/ndk, kotlin/compose, gradle, etc.)
- `confidence`: Initial confidence score (0-100)
- `applied_count`: Number of times this pattern was applied
- `success_count`: Number of times this fix succeeded

## Adding New Patterns

When a new error type is discovered:
1. Add the pattern to `patterns.json`
2. Document the fix strategy in `knowledge.md`
3. Update the agent logic if needed

## Self-Improvement

The agent automatically:
- Tracks success rates of patterns
- Updates confidence scores based on outcomes
- Generalizes fixes to broader categories
- Creates new patterns from successful fixes






