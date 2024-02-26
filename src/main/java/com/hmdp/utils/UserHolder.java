package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

public class UserHolder {
    //定义一个静态ThreadLocal常量，泛型是UserDTO
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO userDTO){
        tl.set(userDTO);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
