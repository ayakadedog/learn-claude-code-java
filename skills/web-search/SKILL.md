---
name: web-search
description: Search the web for information and documentation
---

## Web Search Skill

When you need to search the web for information:

### When to Use
- Looking up documentation for libraries/frameworks
- Finding solutions to specific errors
- Researching best practices
- Checking for security vulnerabilities
- Finding current version information

### Search Strategies

1. **Specific Error Messages**
   - Include the exact error message in quotes
   - Add relevant context (language, framework version)

2. **Documentation Lookup**
   - Use `site:docs.example.com` to search specific documentation
   - Include version number if relevant

3. **Code Examples**
   - Add "example" or "tutorial" to search query
   - Include language/framework name

### Using the run Tool
```bash
# Using curl with a search API (if available)
curl -s "https://api.example.com/search?q=your+query"

# Or use command-line tools like:
# - wget for downloading web content
# - lynx or w3m for text-based browsing
```

### Tips
- Prefer official documentation over random blog posts
- Check the date of information for relevance
- Verify information from multiple sources when possible
- Be aware of version-specific differences
