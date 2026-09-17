import { useEffect, useState } from 'react'

/** Valor que só muda depois de `delay` ms sem alterações, para não consultar a API a cada tecla. */
export function useDebouncedValue<T>(value: T, delay = 300) {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return debounced
}
