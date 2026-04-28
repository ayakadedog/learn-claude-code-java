---
name: pdf
description: Process PDF files - read, extract text, handle tables
---

## PDF Processing Skill

When working with PDF files, follow these guidelines:

### Reading PDF Files
1. Use `run` tool with `pdftotext` command if available
2. Alternative: Use Python with PyPDF2 or pdfplumber
3. For encrypted PDFs, ask user for password

### Extracting Tables
1. Use `pdftotext -layout` to preserve table structure
2. For complex tables, recommend using Python's pdfplumber or tabula-py

### Common Commands
```bash
# Extract text from PDF
pdftotext input.pdf output.txt

# Extract with layout preserved
pdftotext -layout input.pdf output.txt

# Get PDF info
pdfinfo input.pdf
```

### Handling Issues
- If PDF is password protected, ask user for password
- If text extraction fails, try OCR tools like tesseract
- For scanned PDFs, recommend OCR preprocessing
