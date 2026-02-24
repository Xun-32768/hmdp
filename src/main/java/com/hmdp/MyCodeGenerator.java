package com.hmdp;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.engine.VelocityTemplateEngine;

import java.util.Collections;

public class MyCodeGenerator {
    public static void main(String[] args) {
        FastAutoGenerator.create("jdbc:mysql://localhost:3306/hmdp?serverTimezone=UTC", "root", "060216")
                .globalConfig(builder -> {
                    builder.author("axun") // 设置作者
                            .outputDir(System.getProperty("user.dir") + "/src/main/java"); // 指定输出到当前项目的java目录下
                })
                .packageConfig(builder -> {
                    builder.parent("com.hmdp") // 设置父包名
                            // .moduleName("system") // 如果需要模块名可以开启，生成的包会变成 com.hmdp.system
                            .entity("pojo.entity") // 实体类包名
                            .mapper("mapper")
                            .service("service")
                            .serviceImpl("service.impl")
                            .controller("controller")
                            .pathInfo(Collections.singletonMap(OutputFile.xml, System.getProperty("user.dir") + "/src/main/resources/mapper"));
                })
                .strategyConfig(builder -> {
                    builder.addInclude("tb_user_info") // 这里填入你想要生成的【表名】
                            .addTablePrefix("tb_") // 如果表名带 tb_ 前缀，生成类名时会自动去掉
                            .entityBuilder()
                            .enableLombok() // 使用Lombok
                            .enableChainModel() // 开启你之前问的链式调用
                            .controllerBuilder()
                            .enableRestStyle(); // 生成 @RestController
                })
                .templateEngine(new VelocityTemplateEngine()) // 使用默认引擎
                .execute();
    }
}
