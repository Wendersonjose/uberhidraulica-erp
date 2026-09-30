import { useQuery } from '@tanstack/react-query'
import { financeApi, financeKeys, type PaymentMethod } from '../../api/finance'

/** Formas de pagamento ativas; dinheiro é validado pelo backend contra a sessão de caixa aberta. */
export function PaymentMethodSelect({ value, onChange, onMethodChange }: {
  value: string
  onChange: (id: string) => void
  onMethodChange?: (method: PaymentMethod | undefined) => void
}) {
  const methods = useQuery({ queryKey: financeKeys.methods, queryFn: financeApi.paymentMethods })
  return <label>Forma de pagamento *
    <select className="select" value={value} onChange={event => {
      const id = event.target.value
      onChange(id)
      onMethodChange?.(methods.data?.find(method => method.id === id))
    }}>
      <option value="">Selecione</option>
      {methods.data?.filter(method => method.active).map(method =>
        <option key={method.id} value={method.id}>
          {method.name}{method.cashSessionRequired ? ' — dinheiro em caixa' : ''}
        </option>)}
    </select>
  </label>
}
