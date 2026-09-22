package br.com.uberhidraulica.erp.workorder;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TASK-0012: abertura, status configuráveis, Kanban, histórico, execução, finalização, entrega e cancelamento. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0012WorkOrderWorkflowIntegrationTest {
    static final String ABERTA = "20000000-0000-0000-0000-000000000001";
    static final String DIAGNOSTICO = "20000000-0000-0000-0000-000000000002";
    static final String REPROVADA = "20000000-0000-0000-0000-000000000005";
    static final String EXECUCAO = "20000000-0000-0000-0000-000000000006";
    static final String FINALIZADA = "20000000-0000-0000-0000-000000000007";

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Workflow");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> "owner-workflow@example.test");
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> "workflow-bootstrap-password");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    String customer;
    String vehicle;
    String service;

    @BeforeEach
    void clean() throws Exception {
        br.com.uberhidraulica.erp.support.CommercialFixtures.clean(jdbc);
        jdbc.update("delete from workorder.work_order_product");
        jdbc.update("delete from workorder.work_order_service");
        jdbc.update("delete from workorder.work_order_status_history");
        jdbc.update("delete from workorder.work_order");
        jdbc.update("delete from workorder.status where id::text not like '20000000-0000-0000-0000-00000000000%'");
        jdbc.update("update workorder.status set active = true, stage_default = true, position = cast(right(id::text, 1) as int) * 10, name = case right(id::text, 1) "
                + "when '1' then 'Aberta' when '2' then 'Em diagnóstico' when '3' then 'Aguardando aprovação' when '4' then 'Aprovada' when '5' then 'Reprovada' "
                + "when '6' then 'Em execução' when '7' then 'Finalizada' when '8' then 'Entregue' else 'Cancelada' end");
        jdbc.update("delete from servicecatalog.service");
        jdbc.update("delete from crm.vehicle_ownership");
        jdbc.update("delete from crm.vehicle");
        jdbc.update("delete from crm.customer");
        customer = id(send(post("/api/customers"), "{\"personType\":\"PF\",\"name\":\"Cliente Fluxo\",\"phone\":\"34999990000\"}"));
        vehicle = id(send(post("/api/vehicles"), "{\"customerId\":\"" + customer + "\",\"plate\":\"FLX1A11\",\"manufacturer\":\"Scania\",\"model\":\"R450\"}"));
        service = id(send(post("/api/services"), "{\"name\":\"Revisão\",\"basePrice\":\"100.00\"}"));
    }

    @Test
    void newEndpointsRequireAuthenticationAndCsrf() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/api/work-orders/board")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/work-order-statuses")).andExpect(status().isUnauthorized());
        for (String action : List.of("start-execution", "finish", "deliver"))
            mvc.perform(post("/api/work-orders/{id}/" + action, id).with(user("operator"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/work-orders/{id}/cancel", id).with(user("operator")).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void opensWithComplaintOptionalMileageInitialStatusAndHistory() throws Exception {
        send(post("/api/work-orders"), "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle + "\"}").andExpect(status().isBadRequest());
        String order = id(open("{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle + "\",\"complaint\":\"  Barulho ao virar  \",\"notes\":\"Cliente aguarda\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryMileage").doesNotExist())
                .andExpect(jsonPath("$.complaint").value("Barulho ao virar"))
                .andExpect(jsonPath("$.notes").value("Cliente aguarda"))
                .andExpect(jsonPath("$.status").value("ABERTA"))
                .andExpect(jsonPath("$.statusInfo.id").value(ABERTA))
                .andExpect(jsonPath("$.number").isNumber()));
        history(order).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fromStatusId").doesNotExist())
                .andExpect(jsonPath("$[0].toStatusName").value("Aberta"))
                .andExpect(jsonPath("$[0].automatic").value(true));

        send(put("/api/work-orders/" + order), "{\"entryMileage\":150000,\"complaint\":\"Barulho e vazamento\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.entryMileage").value(150000)).andExpect(jsonPath("$.notes").doesNotExist());
    }

    @Test
    void refusesInactiveCustomerOrVehicle() throws Exception {
        send(post("/api/vehicles/" + vehicle + "/inactivate"), "").andExpect(status().isOk());
        open(orderBody()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VEHICLE_INACTIVE"));
        send(post("/api/vehicles/" + vehicle + "/reactivate"), "").andExpect(status().isOk());
        send(post("/api/customers/" + customer + "/inactivate"), "").andExpect(status().isOk());
        open(orderBody()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
        assertThat(jdbc.queryForObject("select count(*) from workorder.work_order", Long.class)).isZero();
    }

    @Test
    void manualMovesStayWithinOperationalStages() throws Exception {
        String order = id(open(orderBody()));
        move(order, DIAGNOSTICO, "Técnico avaliando").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EM_DIAGNOSTICO"));
        move(order, DIAGNOSTICO, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_ALREADY_IN_STATUS"));
        move(order, FINALIZADA, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_STATUS_TRANSITION_NOT_ALLOWED"));
        move(order, UUID.randomUUID().toString(), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STATUS_NOT_FOUND"));

        String waiting = id(send(post("/api/work-order-statuses"), "{\"name\":\"Aguardando peça\",\"stage\":\"EM_EXECUCAO\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.stageDefault").value(false)));
        send(post("/api/work-order-statuses/" + waiting + "/inactivate"), "").andExpect(status().isOk());
        move(order, waiting, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_STATUS_INACTIVE"));

        move(order, REPROVADA, null).andExpect(status().isOk());
        action(order, "start-execution").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_STATUS_TRANSITION_NOT_ALLOWED"));
        history(order).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].reason").value("Técnico avaliando"))
                .andExpect(jsonPath("$[1].automatic").value(false));
    }

    @Test
    void executesFinishesAndDeliversRecordingTimestampsAndClosingTheOrder() throws Exception {
        String order = id(open(orderBody()));
        action(order, "finish").andExpect(status().isConflict());
        action(order, "start-execution").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EM_EXECUCAO"))
                .andExpect(jsonPath("$.lifecycle.executionStartedAt").exists());
        action(order, "start-execution").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_ALREADY_IN_EXECUTION"));
        action(order, "deliver").andExpect(status().isConflict());
        send(post("/api/work-orders/" + order + "/services"), "{\"serviceId\":\"" + service + "\"}").andExpect(status().isCreated());
        // DR-0015, F-02: sem base comercial aprovada a finalização é recusada; o assunto aqui é o fluxo da OS.
        action(order, "finish").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_WITHOUT_BILLING_BASIS"));
        br.com.uberhidraulica.erp.support.CommercialFixtures.approvedQuote(jdbc, java.util.UUID.fromString(order), "Serviço", new java.math.BigDecimal("150.00"));

        action(order, "finish").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINALIZADA"))
                .andExpect(jsonPath("$.lifecycle.finishedAt").exists());
        send(post("/api/work-orders/" + order + "/services"), "{\"serviceId\":\"" + service + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_CLOSED"));
        send(put("/api/work-orders/" + order), "{\"complaint\":\"Alterada\"}").andExpect(status().isConflict());
        send(post("/api/work-orders/" + order + "/cancel"), "{\"reason\":\"Desistiu\"}").andExpect(status().isConflict());
        move(order, ABERTA, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_CLOSED"));

        action(order, "deliver").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENTREGUE"))
                .andExpect(jsonPath("$.lifecycle.deliveredAt").exists())
                .andExpect(jsonPath("$.services.length()").value(1));
        action(order, "deliver").andExpect(status().isConflict());
        history(order).andExpect(jsonPath("$.length()").value(4)).andExpect(jsonPath("$[3].toStatusName").value("Entregue"));
        mvc.perform(get("/api/work-orders?customerId=" + customer).with(user("operator"))).andExpect(jsonPath("$[0].status").value("ENTREGUE"));
    }

    @Test
    void cancelsWithMandatoryReasonAndNeverReturnsToTheFlow() throws Exception {
        String order = id(open(orderBody()));
        send(post("/api/work-orders/" + order + "/cancel"), "{\"reason\":\" \"}").andExpect(status().isBadRequest());
        send(post("/api/work-orders/" + order + "/cancel"), "{\"reason\":\"Cliente desistiu\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADA"))
                .andExpect(jsonPath("$.lifecycle.cancellationReason").value("Cliente desistiu"))
                .andExpect(jsonPath("$.lifecycle.cancelledAt").exists());
        action(order, "start-execution").andExpect(status().isConflict());
        move(order, ABERTA, null).andExpect(status().isConflict());
        history(order).andExpect(jsonPath("$[1].reason").value("Cliente desistiu"));
        assertThat(jdbc.queryForObject("select count(*) from workorder.work_order", Long.class)).isEqualTo(1);
    }

    @Test
    void configuresStatusesDefaultsOrderAndBoard() throws Exception {
        String order = id(open(orderBody()));
        String cancelled = id(open(orderBody()));
        send(post("/api/work-orders/" + cancelled + "/cancel"), "{\"reason\":\"Duplicada\"}").andExpect(status().isOk());

        String waiting = id(send(post("/api/work-order-statuses"), "{\"name\":\"Aguardando peça\",\"stage\":\"EM_EXECUCAO\"}"));
        send(post("/api/work-order-statuses"), "{\"name\":\"aguardando PEÇA\",\"stage\":\"EM_EXECUCAO\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATUS_NAME_ALREADY_EXISTS"));
        send(post("/api/work-order-statuses/" + EXECUCAO + "/inactivate"), "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATUS_DEFAULT_CANNOT_BE_INACTIVATED"));
        send(post("/api/work-order-statuses/" + waiting + "/make-default"), "").andExpect(status().isOk()).andExpect(jsonPath("$.stageDefault").value(true));
        send(post("/api/work-order-statuses/" + EXECUCAO + "/inactivate"), "").andExpect(status().isOk());

        action(order, "start-execution").andExpect(status().isOk()).andExpect(jsonPath("$.statusInfo.name").value("Aguardando peça"));
        mvc.perform(get("/api/work-order-statuses").with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.status.name == 'Aguardando peça')].orderCount").value(1));

        mvc.perform(get("/api/work-orders/board").with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.status.name == 'Em execução')]").isEmpty())
                .andExpect(jsonPath("$[?(@.status.name == 'Aguardando peça')].orders[0].customerName").value("Cliente Fluxo"))
                .andExpect(jsonPath("$[?(@.status.name == 'Aguardando peça')].orders[0].vehicleLabel").value("Scania R450 • FLX1A11"))
                .andExpect(jsonPath("$[?(@.status.name == 'Cancelada')].orders.length()").value(1));
        jdbc.update("update workorder.work_order set cancelled_at = now() - interval '60 days', opened_at = now() - interval '61 days' where id = ?", UUID.fromString(cancelled));
        mvc.perform(get("/api/work-orders/board").with(user("operator")))
                .andExpect(jsonPath("$[?(@.status.name == 'Cancelada')].orders.length()").value(0));
        mvc.perform(get("/api/work-orders/board?closedDays=90").with(user("operator")))
                .andExpect(jsonPath("$[?(@.status.name == 'Cancelada')].orders.length()").value(1));

        String statuses = mvc.perform(get("/api/work-order-statuses").with(user("operator"))).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(statuses, "$[*].status.id");
        send(put("/api/work-order-statuses/order"), "{\"statusIds\":[\"" + ids.get(0) + "\"]}").andExpect(status().isBadRequest());
        List<String> reversed = new java.util.ArrayList<>(ids);
        java.util.Collections.reverse(reversed);
        send(put("/api/work-order-statuses/order"), "{\"statusIds\":[\"" + String.join("\",\"", reversed) + "\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(reversed.get(0)));
    }

    @Test
    void postgresEnforcesOneDefaultPerStageAndCancellationConsistency() throws Exception {
        assertThatThrownBy(() -> jdbc.update("insert into workorder.status(id,name,stage,position,active,stage_default,created_at,updated_at) values (?,'Outra aberta','ABERTA',1,true,true,now(),now())", UUID.randomUUID()))
                .hasMessageContaining("uq_workorder_status_stage_default");
        assertThatThrownBy(() -> jdbc.update("insert into workorder.status(id,name,stage,position,active,stage_default,created_at,updated_at) values (?,'Inativa padrão','ABERTA',1,false,true,now(),now())", UUID.randomUUID()))
                .hasMessageContaining("ck_workorder_status_default_active");
        String order = id(open(orderBody()));
        assertThatThrownBy(() -> jdbc.update("update workorder.work_order set cancellation_reason = 'sem data' where id = ?", UUID.fromString(order)))
                .hasMessageContaining("ck_work_order_cancellation");
    }

    private String orderBody() {
        return "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle + "\",\"entryMileage\":1000,\"complaint\":\"Direção pesada\"}";
    }

    private ResultActions open(String body) throws Exception { return send(post("/api/work-orders"), body); }

    private ResultActions move(String order, String statusId, String reason) throws Exception {
        return send(post("/api/work-orders/" + order + "/status"), "{\"statusId\":\"" + statusId + "\"" + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    private ResultActions action(String order, String action) throws Exception {
        // Finalizar exige FINANCE_BILL (revisão TASK-0015, F3), que só uma sessão real do IAM carrega.
        if (action.equals("finish")) {
            var sessions = new br.com.uberhidraulica.erp.support.ApiSessions(mvc, "workflow-operational-password");
            return sessions.send(sessions.owner("owner-workflow@example.test", "workflow-bootstrap-password"),
                    post("/api/work-orders/" + order + "/finish"), "");
        }
        return send(post("/api/work-orders/" + order + "/" + action), "");
    }

    private ResultActions history(String order) throws Exception {
        return mvc.perform(get("/api/work-orders/{id}/status-history", order).with(user("operator"))).andExpect(status().isOk());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.with(user("operator")).with(csrf());
        if (!body.isEmpty()) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
