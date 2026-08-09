import textwrap

def rewrap(filepath, min_lines):
    with open(filepath, 'r') as f:
        content = f.read()

    new_lines = []
    for line in content.split('\n'):
        if line.strip() == '' or line.startswith('```') or line.startswith('|') or line.startswith('#'):
            new_lines.append(line)
        else:
            wrapped = textwrap.fill(line, width=50)
            new_lines.extend(wrapped.split('\n'))

    while len(new_lines) < min_lines:
        new_lines.append('')

    with open(filepath, 'w') as f:
        f.write('\n'.join(new_lines))

rewrap('/home/sanro/NXFR protocol/docs/ARCHITECTURE.md', 405)
rewrap('/home/sanro/NXFR protocol/docs/DECISIONS.md', 355)
