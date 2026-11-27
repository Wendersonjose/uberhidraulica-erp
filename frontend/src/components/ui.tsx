import{useId,useState,type ComponentProps,type ReactNode}from'react'
export function PageHeader({title,subtitle,children}:{title:string;subtitle:string;children?:ReactNode}){return <header className="page-header"><div><h1>{title}</h1><p>{subtitle}</p></div><div className="actions">{children}</div></header>}
export function Field({label,error,children,className=''}:{label:string;error?:string;children:ReactNode;className?:string}){return <div className={`field ${className}`}><label>{label}{children}</label>{error&&<span role="alert" className="error-text">{error}</span>}</div>}
export function State({loading,error,empty,children}:{loading:boolean;error:unknown;empty:boolean;children:ReactNode}){if(loading)return <div className="card state">Carregando…</div>;if(error)return <div role="alert" className="notice error">Não foi possível carregar os dados. Tente novamente.</div>;if(empty)return <div className="card state">Nenhum registro encontrado.</div>;return <>{children}</>}
export const Badge=({children,tone='success'}:{children:ReactNode;tone?:'success'|'warning'|'info'})=><span className={`badge ${tone}`}>{children}</span>

/**
 * Campo de senha com botão para mostrar/ocultar o texto digitado.
 *
 * <p>O botão fica fora do {@code <label>} para não entrar no nome acessível do campo, e é {@code type="button"}
 * para não enviar o formulário.
 */
export function PasswordField({label,error,...input}:{label:string;error?:string}&Omit<ComponentProps<'input'>,'type'|'id'|'className'>){
  const id=useId(),[visible,setVisible]=useState(false)
  return <div className="field">
    <label htmlFor={id}>{label}</label>
    <div className="password-input">
      <input {...input} id={id} className="input" type={visible?'text':'password'} aria-invalid={error?true:undefined}/>
      <button type="button" className="password-toggle" aria-label={visible?`Ocultar ${label.toLowerCase()}`:`Mostrar ${label.toLowerCase()}`}
        aria-pressed={visible} aria-controls={id} title={visible?'Ocultar senha':'Mostrar senha'} onClick={()=>setVisible(v=>!v)}>
        {visible?<EyeOffIcon/>:<EyeIcon/>}
      </button>
    </div>
    {error&&<span role="alert" className="error-text">{error}</span>}
  </div>
}
const EyeIcon=()=><svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>
const EyeOffIcon=()=><svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M10.6 5.1A10.8 10.8 0 0 1 12 5c6.5 0 10 7 10 7a17.6 17.6 0 0 1-2.6 3.5M6.6 6.6C3.7 8.4 2 12 2 12s3.5 7 10 7a9.7 9.7 0 0 0 5.4-1.6"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/><path d="M2 2l20 20"/></svg>
