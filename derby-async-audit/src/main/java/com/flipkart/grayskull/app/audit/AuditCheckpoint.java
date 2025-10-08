package com.flipkart.grayskull.app.audit;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class AuditCheckpoint {
    @Id
    private String id;

    private String nodeName;

    private long logId;

    public AuditCheckpoint(String nodeName) {
        this.nodeName = nodeName;
    }
}