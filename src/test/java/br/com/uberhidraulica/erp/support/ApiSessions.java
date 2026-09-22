package br.com.uberhidraulica.erp.support;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sessões reais do IAM para testes de integração que dependem de permissão.
 *
 * <p>Um usuário simulado não tem identidade nem permissões efetivas; as regras de autorização do
 * backend só são exercidas com login de verdade, CSRF incluído.</p>
 */
public final class ApiSessions {
    private final MockMvc mvc;
    private final String password;

    public ApiSessions(MockMvc mvc, String password) {
        this.mvc = mvc;
        this.password = password;
    }

    public record Session(Cookie cookie, String csrfHeader, String csrfToken) {}

    /** Sessão do proprietário criado pelo bootstrap, trocando a senha inicial na primeira vez. */
    public Session owner(String email, String bootstrapPassword) throws Exception {
        Session bootstrap = login(email, bootstrapPassword, true);
        if (bootstrap != null)
            send(bootstrap, post("/api/iam/password/change"), "{\"currentPassword\":\"" + bootstrapPassword
                    + "\",\"newPassword\":\"" + password + "\"}").andExpect(status().isNoContent());
        return login(email, password, false);
    }

    /** Cria (ou reaproveita) um usuário com o perfil informado e devolve a sessão dele. */
    public Session user(Session owner, String email, String profileCode) throws Exception {
        Session existing = login(email, password, true);
        if (existing != null) return existing;
        String created = send(owner, post("/api/iam/users"), "{\"name\":\"Usuario " + profileCode + "\",\"email\":\"" + email
                + "\",\"profileCode\":\"" + profileCode + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String temporary = JsonPath.read(created, "$.temporaryPassword");
        Session first = login(email, temporary, false);
        send(first, post("/api/iam/password/change"), "{\"currentPassword\":\"" + temporary + "\",\"newPassword\":\"" + password + "\"}")
                .andExpect(status().isNoContent());
        return login(email, password, false);
    }

    public ResultActions send(Session session, MockHttpServletRequestBuilder builder, String body) throws Exception {
        builder.cookie(session.cookie()).header(session.csrfHeader(), session.csrfToken());
        if (body != null && !body.isEmpty()) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(builder);
    }

    public ResultActions read(Session session, String path) throws Exception {
        return mvc.perform(get(path).cookie(session.cookie()));
    }

    private Session login(String email, String secret, boolean optional) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/iam/csrf")).andExpect(status().isOk()).andReturn();
        Cookie anonymous = csrf.getResponse().getCookie("SESSION");
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        String header = JsonPath.read(csrf.getResponse().getContentAsString(), "$.headerName");
        MvcResult result = mvc.perform(post("/api/iam/auth/login").cookie(anonymous).header(header, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"" + secret + "\"}")).andReturn();
        if (result.getResponse().getStatus() != 200) {
            if (optional) return null;
            throw new IllegalStateException("Login falhou: " + result.getResponse().getStatus());
        }
        return new Session(result.getResponse().getCookie("SESSION"), header, token);
    }
}
