import type { PersonType, Supplier, SupplierInput } from '../../api/purchasing'
import { formatTaxId, isValidCnpj, isValidCpf } from '../../utils/taxId'

export const STATES = ['AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG', 'PA', 'PB', 'PR', 'PE', 'PI', 'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO']
export const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

export type FormState = {
  personType: PersonType; legalName: string; tradeName: string; document: string; stateRegistration: string; phone: string; email: string
  zipCode: string; street: string; number: string; complement: string; district: string; city: string; state: string
  paymentTerms: string; preferredPaymentMethod: string; usualDueDay: string; creditLimit: string; commercialNotes: string
  relatedParty: boolean; relatedPartyNote: string
}

export const emptyForm: FormState = {
  personType: 'PJ', legalName: '', tradeName: '', document: '', stateRegistration: '', phone: '', email: '',
  zipCode: '', street: '', number: '', complement: '', district: '', city: '', state: '',
  paymentTerms: '', preferredPaymentMethod: '', usualDueDay: '', creditLimit: '', commercialNotes: '', relatedParty: false, relatedPartyNote: '',
}

export const fromSupplier = (s: Supplier): FormState => ({
  personType: s.personType, legalName: s.legalName, tradeName: s.tradeName ?? '', document: s.document ? formatTaxId(s.document) : '',
  stateRegistration: s.stateRegistration ?? '', phone: s.phone ?? '', email: s.email ?? '',
  zipCode: s.address.zipCode ?? '', street: s.address.street ?? '', number: s.address.number ?? '', complement: s.address.complement ?? '',
  district: s.address.district ?? '', city: s.address.city ?? '', state: s.address.state ?? '',
  paymentTerms: s.commercialTerms.paymentTerms ?? '', preferredPaymentMethod: s.commercialTerms.preferredPaymentMethod ?? '',
  usualDueDay: s.commercialTerms.usualDueDay == null ? '' : String(s.commercialTerms.usualDueDay),
  creditLimit: s.commercialTerms.creditLimit == null ? '' : String(s.commercialTerms.creditLimit),
  commercialNotes: s.commercialTerms.notes ?? '', relatedParty: s.relatedParty, relatedPartyNote: s.relatedPartyNote ?? '',
})

export const blankToNull = (value: string) => value.trim() || null

/** Validação de conforto; o backend repete e decide. */
export function validateSupplierForm(form: FormState): Partial<Record<keyof FormState, string>> {
  const errors: Partial<Record<keyof FormState, string>> = {}
  if (form.legalName.trim().length < 2) errors.legalName = form.personType === 'PF' ? 'Informe o nome' : 'Informe a razão social'
  if (form.document.trim() && !(form.personType === 'PF' ? isValidCpf(form.document) : isValidCnpj(form.document)))
    errors.document = form.personType === 'PF' ? 'CPF inválido' : 'CNPJ inválido'
  if (form.email.trim() && !EMAIL.test(form.email.trim())) errors.email = 'E-mail inválido'
  if (form.zipCode.trim() && form.zipCode.replace(/\D/g, '').length !== 8) errors.zipCode = 'CEP deve ter 8 dígitos'
  if (form.state && !STATES.includes(form.state)) errors.state = 'UF inválida'
  if (form.usualDueDay.trim() && !(/^\d+$/.test(form.usualDueDay.trim()) && Number(form.usualDueDay) >= 1 && Number(form.usualDueDay) <= 31))
    errors.usualDueDay = 'Informe um dia entre 1 e 31'
  if (form.creditLimit.trim() && !/^\d+([.,]\d{1,2})?$/.test(form.creditLimit.trim())) errors.creditLimit = 'Informe um valor com até duas casas decimais'
  return errors
}

export function toInput(form: FormState, version?: number): SupplierInput {
  return {
    personType: form.personType, legalName: form.legalName.trim(), tradeName: blankToNull(form.tradeName), document: blankToNull(form.document),
    stateRegistration: blankToNull(form.stateRegistration), phone: blankToNull(form.phone), email: blankToNull(form.email),
    address: { zipCode: blankToNull(form.zipCode), street: blankToNull(form.street), number: blankToNull(form.number),
      complement: blankToNull(form.complement), district: blankToNull(form.district), city: blankToNull(form.city), state: blankToNull(form.state) },
    commercialTerms: { paymentTerms: blankToNull(form.paymentTerms), preferredPaymentMethod: blankToNull(form.preferredPaymentMethod),
      usualDueDay: form.usualDueDay.trim() ? Number(form.usualDueDay) : null,
      creditLimit: form.creditLimit.trim() ? Number(form.creditLimit.trim().replace(',', '.')) : null, notes: blankToNull(form.commercialNotes) },
    relatedParty: form.relatedParty, relatedPartyNote: form.relatedParty ? blankToNull(form.relatedPartyNote) : null,
    version,
  }
}

