import { z } from 'zod'

// A CSP do nginx não libera `unsafe-eval`. Por padrão o zod 4 testa `new Function('')` para compilar os schemas
// (JIT) e o navegador registra uma violação de CSP a cada carga de página. Sem JIT o zod valida igual, só sem o
// atalho de desempenho, e nenhum eval é tentado. Este módulo precisa ser o primeiro import de main.tsx.
z.config({ jitless: true })
