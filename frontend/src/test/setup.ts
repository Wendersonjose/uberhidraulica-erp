import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// findBy*/waitFor usam 1 s por padrão. Com a suíte inteira em paralelo numa máquina carregada (jsdom por arquivo, CI
// compartilhado), telas que dependem de várias respostas simuladas passam isoladas e estouram o prazo em conjunto.
// 5 s mantém a asserção igual (ainda falha se o elemento nunca aparecer) e remove a intermitência por tempo.
configure({ asyncUtilTimeout: 5000 })

afterEach(() => { cleanup(); vi.restoreAllMocks() })
