package com.hmdp.dto;

import lombok.Data;

//这是一个User的精简类，用于将user对象的部分字段存储在session中防止泄露

@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}
