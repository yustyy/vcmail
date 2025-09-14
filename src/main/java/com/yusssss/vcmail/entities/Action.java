package com.yusssss.vcmail.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "actions")
public class Action {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private String id;

    @Column(name = "action_name")
    private String actionName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "action_parameters", joinColumns = @JoinColumn(name = "action_id"))
    @MapKeyColumn(name = "parameter_key")
    @Column(name = "parameter_value")
    private Map<String, String> parameters;

}
