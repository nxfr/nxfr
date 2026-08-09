# Contributing to the Documentation

Thank you for your interest in contributing to the NXFR documentation! High-quality documentation is just as important as the code itself.

## General Guidelines

For an overview of the project's general contribution guidelines, pull request process, and coding standards, please refer to the root [CONTRIBUTING.md](https://github.com/nxfr/nxfr/blob/main/CONTRIBUTING.md) file.

Please also ensure you read and adhere to our [CODE_OF_CONDUCT.md](https://github.com/nxfr/nxfr/blob/main/CODE_OF_CONDUCT.md) in all interactions within the community.

## Development Workflow

The documentation site is built using [MkDocs](https://www.mkdocs.org/) with the [Material for MkDocs](https://squidfunk.github.io/mkdocs-material/) theme.

To run the documentation site locally and preview your changes:

1. **Install Python and pip** if you haven't already.
2. **Install the dependencies**:
   ```bash
   pip install mkdocs-material mkdocs-git-revision-date-localized-plugin
   ```
3. **Run the development server**: Navigate to the `docs-site` directory and run:
   ```bash
   mkdocs serve
   ```
4. Open your browser to `http://127.0.0.1:8000/`. The site will auto-reload as you save your Markdown files.

## How to Contribute to Docs Specifically

1. Documentation files are located in the `docs-site/docs/` directory.
2. We use standard Markdown format, augmented by `mkdocs-material` extensions like Admonitions and Tabbed blocks.
3. Keep the tone professional, clear, and concise.
4. When adding new pages, remember to update the `nav` section in `docs-site/mkdocs.yml`.
