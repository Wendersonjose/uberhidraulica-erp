import { expect, test } from 'vitest'
import { formatTaxId, isValidCnpj, isValidCpf, normalizeTaxId } from '../utils/taxId'
import { emptyForm, validateSupplierForm } from '../features/purchasing/supplierForm'

test('CNPJ numérico, com máscara e alfanumérico (NT 2025.001) são validados pelo dígito verificador', () => {
  expect(isValidCnpj('67.757.350/0001-78')).toBe(true)
  expect(isValidCnpj('01637003000101')).toBe(true)
  expect(isValidCnpj('12.ABC.345/01DE-35')).toBe(true)
  expect(isValidCnpj('67757350000179')).toBe(false)
  expect(isValidCnpj('12ABC34501DE36')).toBe(false)
  expect(isValidCnpj('00000000000000')).toBe(false)
  expect(isValidCnpj('123')).toBe(false)
})

test('CPF é validado pelos dois dígitos verificadores', () => {
  expect(isValidCpf('529.982.247-25')).toBe(true)
  expect(isValidCpf('52998224726')).toBe(false)
  expect(isValidCpf('11111111111')).toBe(false)
  expect(isValidCpf('5299822472')).toBe(false)
})

test('normaliza e formata sem perder as letras do CNPJ alfanumérico', () => {
  expect(normalizeTaxId('12.abc.345/01de-35')).toBe('12ABC34501DE35')
  expect(formatTaxId('12ABC34501DE35')).toBe('12.ABC.345/01DE-35')
  expect(formatTaxId('67757350000178')).toBe('67.757.350/0001-78')
  expect(formatTaxId('52998224725')).toBe('529.982.247-25')
  expect(formatTaxId('*********25')).toBe('*********25')
  expect(formatTaxId(null)).toBe('—')
})

test('formulário de fornecedor: valida documento conforme o tipo, e-mail, CEP, dia de vencimento e limite', () => {
  const base = { ...emptyForm, legalName: 'Fornecedor Teste' }
  expect(validateSupplierForm(base)).toEqual({})
  expect(validateSupplierForm({ ...base, document: '67.757.350/0001-78' })).toEqual({})
  expect(validateSupplierForm({ ...base, document: '52998224725' }).document).toBe('CNPJ inválido')
  expect(validateSupplierForm({ ...base, personType: 'PF', document: '67757350000178' }).document).toBe('CPF inválido')
  expect(validateSupplierForm({ ...base, personType: 'PF', document: '529.982.247-25' })).toEqual({})
  expect(validateSupplierForm({ ...base, email: 'sem-arroba' }).email).toBe('E-mail inválido')
  expect(validateSupplierForm({ ...base, zipCode: '123' }).zipCode).toBe('CEP deve ter 8 dígitos')
  expect(validateSupplierForm({ ...base, zipCode: '38400-000' })).toEqual({})
  expect(validateSupplierForm({ ...base, usualDueDay: '0' }).usualDueDay).toBeTruthy()
  expect(validateSupplierForm({ ...base, usualDueDay: '32' }).usualDueDay).toBeTruthy()
  expect(validateSupplierForm({ ...base, usualDueDay: '31' })).toEqual({})
  expect(validateSupplierForm({ ...base, creditLimit: '10.005' }).creditLimit).toBeTruthy()
  expect(validateSupplierForm({ ...base, creditLimit: '1500,50' })).toEqual({})
  expect(validateSupplierForm({ ...base, legalName: ' ' }).legalName).toBe('Informe a razão social')
})
