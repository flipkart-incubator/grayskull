package com.flipkart.grayskull.exception;

import com.flipkart.grayskull.models.dto.response.ResponseTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.web.servlet.error.AbstractErrorController;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Slf4j
public class GrayskullErrorController extends AbstractErrorController {
    public GrayskullErrorController(ErrorAttributes errorAttributes) {
        super(errorAttributes);
    }

    @RequestMapping("/error")
    public ResponseEntity<ResponseTemplate<Void>> handleError(HttpServletRequest request) {
        HttpStatus status = this.getStatus(request);
        Map<String, Object> errorAttributes = this.getErrorAttributes(request, ErrorAttributeOptions.defaults().including(ErrorAttributeOptions.Include.MESSAGE).including(ErrorAttributeOptions.Include.EXCEPTION));
        if (errorAttributes.containsKey("exception")) {
            log.error("Exception occurred", (Exception) errorAttributes.get("exception"));
        }
        return new ResponseEntity<>(ResponseTemplate.error(errorAttributes.get("message").toString(), errorAttributes.get("error").toString()), status);
    }
}
