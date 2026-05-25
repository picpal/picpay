package com.picpay.token.domain;

import com.picpay.common.entity.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "easy_pay_methods", schema = "token")
public class EasyPayMethod extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "method_id", nullable = false, unique = true)
    private String methodId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "token_id", nullable = false)
    private String tokenId;

    @Column(name = "method_name")
    private String methodName;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private EasyPayMethodStatus status = EasyPayMethodStatus.ACTIVE;

    protected EasyPayMethod() {}

    public static EasyPayMethod create(String methodId, String userId,
                                       String tokenId, String methodName) {
        EasyPayMethod m = new EasyPayMethod();
        m.methodId = methodId;
        m.userId = userId;
        m.tokenId = tokenId;
        m.methodName = methodName;
        return m;
    }

    public void delete() {
        this.status = EasyPayMethodStatus.DELETED;
    }

    public Long getId() { return id; }
    public String getMethodId() { return methodId; }
    public String getUserId() { return userId; }
    public String getTokenId() { return tokenId; }
    public String getMethodName() { return methodName; }
    public EasyPayMethodStatus getStatus() { return status; }
}
