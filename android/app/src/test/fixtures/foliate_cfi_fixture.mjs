import { readFile } from 'node:fs/promises'

const source = await readFile(new URL('../../../../../assets/foliate-js/src/epubcfi.js', import.meta.url))
const CFI = await import(`data:text/javascript;base64,${source.toString('base64')}`)

const document = { documentElement: null }
function element(id = '', ...children) {
    const node = { nodeType: 1, id, childNodes: children, ownerDocument: document }
    for (const child of children) child.parentNode = node
    return node
}
function text(value) {
    return { nodeType: 3, nodeValue: value, childNodes: [], ownerDocument: document }
}

const refs = [element('part]one'), element('part-two')]
const spine = element('', ...refs)
document.documentElement = element('', element(), element(), spine)
const bases = CFI.fromElements(refs)

const word = text('reader')
const greeting = text('Hello ')
const body = element('', element('', text('First')), element('', greeting, element('', word), text('.')))
document.documentElement = element('', element(), body)
const range = { startContainer: word, startOffset: 3, endContainer: word, endOffset: 3, collapsed: true }
const local = CFI.fromRange(range)
const selection = CFI.fromRange({ startContainer: word, startOffset: 1,
    endContainer: word, endOffset: 4, collapsed: false })
const acrossNodes = CFI.fromRange({ startContainer: greeting, startOffset: 3,
    endContainer: word, endOffset: 4, collapsed: false })

console.log(JSON.stringify({ bases, local, joined: CFI.joinIndir(bases[0], local),
    selection: CFI.joinIndir(bases[0], selection),
    acrossNodes: CFI.joinIndir(bases[0], acrossNodes) }))
