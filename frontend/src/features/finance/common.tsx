import { useQuery } from '@tanstack/react-query'
import { financeApi, financeKeys } from '../../api/finance'

/**
 * Formas de pagamento para nova liquidação. Dinheiro aparece, mas indisponível: exige sessão de caixa,
 * que ainda não existe (DR-0015, F-10).
 */
export function PaymentMethodSelect({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const methods = useQuery({ queryKey: financeKeys.methods, queryFn: financeApi.paymentMethods })
  return <label>Forma de pagamento *
    <select className="select" value={value} onChange={event => onChange(event.target.value)}>
      <option value="">Selecione</option>
      {methods.data?.filter(method => method.active).map(method =>
        <option key={method.id} value={method.id} disabled={method.cashSessionRequired}>
          {method.name}{method.cashSessionRequired ? ' — exige sessão de caixa' : ''}
        </option>)}
    </select>
  </label>
}
