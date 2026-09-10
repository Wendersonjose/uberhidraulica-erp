package br.com.uberhidraulica.erp.workorder.domain;
public class WorkOrderException extends RuntimeException {
    private final String code;
    public WorkOrderException(String code,String message){super(message);this.code=code;}
    public String code(){return code;}
}
