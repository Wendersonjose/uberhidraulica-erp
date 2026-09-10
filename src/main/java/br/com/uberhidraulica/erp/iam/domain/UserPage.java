package br.com.uberhidraulica.erp.iam.domain;

import java.util.List;

public record UserPage(List<IamUser> content, int page, int size, long totalElements, int totalPages) {
    public UserPage {
        content = List.copyOf(content);
    }
}
