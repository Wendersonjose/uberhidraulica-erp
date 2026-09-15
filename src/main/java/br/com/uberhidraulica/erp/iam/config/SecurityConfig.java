package br.com.uberhidraulica.erp.iam.config;

import br.com.uberhidraulica.erp.iam.infrastructure.security.MustChangePasswordFilter;
import br.com.uberhidraulica.erp.iam.infrastructure.security.IamUserDetailsService;
import br.com.uberhidraulica.erp.iam.application.DeniedOperationAuditService;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.web.http.DefaultCookieSerializer;
import java.util.Map;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    /** Superfície pública do orçamento: sem sessão, sem CSRF e sem acesso a nada além dela. */
    private static final String PUBLIC_QUOTE = "/api/public/quotes/**";

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

    @Bean PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }
    @Bean AuthenticationManager authenticationManager(IamUserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean DefaultCookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, MustChangePasswordFilter mustChange,
                                            DeniedOperationAuditService deniedAudit) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/iam/auth/login", "/api/iam/csrf", "/actuator/health").permitAll()
                        // O cliente externo não tem conta: a autorização do orçamento público é o token
                        // opaco do caminho, verificado pelo próprio módulo de Orçamento.
                        .requestMatchers(PUBLIC_QUOTE).permitAll()
                        .anyRequest().authenticated())
                // CSRF protege credencial ambiente do navegador. No fluxo público não existe credencial
                // ambiente: quem não tem o token não consegue nada, e quem tem não precisa da vítima.
                // Exigir CSRF aqui só tornaria o fluxo impossível, sem remover ataque nenhum.
                .csrf(csrf -> csrf.ignoringRequestMatchers(PUBLIC_QUOTE))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .securityContext(context -> context.requireExplicitSave(true))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Autenticação necessária\",\"details\":[]}");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            // A negação precisa chegar ao cliente mesmo que a auditoria falhe: o acesso
                            // foi barrado de qualquer forma, e devolver 500 esconderia isso. A falha de
                            // auditoria fica registrada em log de erro para não passar despercebida.
                            try {
                                deniedAudit.record(request, SecurityContextHolder.getContext().getAuthentication());
                            } catch (RuntimeException auditFailure) {
                                LOGGER.error("Falha ao auditar acesso negado a {} {}",
                                        request.getMethod(), request.getRequestURI(), auditFailure);
                            }
                            response.setStatus(403); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"code\":\"ACCESS_DENIED\",\"message\":\"Acesso negado\",\"details\":[]}");
                        }))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .addFilterAfter(mustChange, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
