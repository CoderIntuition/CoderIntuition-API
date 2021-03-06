package com.coderintuition.CoderIntuition.models;

import com.coderintuition.CoderIntuition.enums.ArgumentType;
import com.coderintuition.CoderIntuition.enums.UnderlyingType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@Entity
@Table(name = "argument")
@Getter
@Setter
@NoArgsConstructor
public class Argument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JsonIgnoreProperties("arguments")
    @ManyToOne
    @JoinColumn(name = "problem_id")
    @NotNull
    private Problem problem;

    @Column(name = "argument_num")
    @Positive
    private Integer argumentNum;

    @Column(name = "type")
    @Enumerated(EnumType.STRING)
    @NotNull
    private ArgumentType type;

    @Column(name = "underlying_type")
    @Enumerated(EnumType.STRING)
    @NotNull
    private UnderlyingType underlyingType;

    @Column(name = "underlying_type_2")
    @Enumerated(EnumType.STRING)
    @NotNull
    private UnderlyingType underlyingType2;
}
