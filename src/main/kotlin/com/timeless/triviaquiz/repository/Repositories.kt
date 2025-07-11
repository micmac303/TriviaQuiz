package com.timeless.triviaquiz.repository

import com.timeless.triviaquiz.entity.User
import org.springframework.data.repository.CrudRepository

interface UserRepository : CrudRepository<User, Long> {
    fun findByLogin(login: String): User?
}