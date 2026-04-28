---
name: code-review
description: Review code for quality, security, and best practices
---

## Code Review Skill

When reviewing code, follow this systematic approach:

### Review Checklist

1. **Code Quality**
   - Readability and maintainability
   - Consistent naming conventions
   - Proper code organization
   - Appropriate comments and documentation

2. **Security**
   - Input validation
   - SQL injection prevention
   - XSS prevention
   - Sensitive data handling (no hardcoded secrets)
   - Proper authentication/authorization

3. **Performance**
   - Efficient algorithms
   - Proper resource management
   - Avoiding memory leaks
   - Database query optimization

4. **Best Practices**
   - DRY (Don't Repeat Yourself)
   - SOLID principles
   - Error handling
   - Logging practices

### Review Output Format

```
## Code Review Summary

### Strengths
- [List positive aspects]

### Issues Found
- **[Severity]**: [Description]
  - Location: [file:line]
  - Suggestion: [How to fix]

### Recommendations
- [General improvement suggestions]
```

### Severity Levels
- **Critical**: Security vulnerabilities, data loss risks
- **High**: Bugs, performance issues
- **Medium**: Code quality, maintainability
- **Low**: Style, minor improvements
