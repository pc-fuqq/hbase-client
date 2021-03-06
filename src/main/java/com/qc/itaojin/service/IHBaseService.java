package com.qc.itaojin.service;

import com.qc.itaojin.exception.ItaojinHBaseException;

import java.util.List;
import java.util.Map;

/**
 * @desc HBase服务类
 * @author fuqinqin
 * @date 2018-07-02
 */
public interface IHBaseService extends IBaseService{

    /**
     * 插入/修改 数据，所有的列数据都放在 f1 列族下
     * */
    void update(String nameSpace, String table, String rowKey, Map<String, String> columns) throws ItaojinHBaseException;

    /**
     * 删除一行数据
     * */
    void delete(String nameSpace, String tableName, String rowKey) throws ItaojinHBaseException;

    /**
     * 修改列族的版本数
     * */
    void updateVersions(String nameSpace, String tableName, String fi, int versions) throws ItaojinHBaseException;

    /**
     * 扫描全表
     * */
    <T> List<T> scanAll(Class<T> clazz) throws ItaojinHBaseException;

}
