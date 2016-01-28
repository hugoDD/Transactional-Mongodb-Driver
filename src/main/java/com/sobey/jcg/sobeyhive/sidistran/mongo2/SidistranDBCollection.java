package com.sobey.jcg.sobeyhive.sidistran.mongo2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.reflect.MethodUtils;
import org.bson.BSONObject;

import com.mongodb.AggregationOptions;
import com.mongodb.AggregationOutput;
import com.mongodb.BasicDBObject;
import com.mongodb.Cursor;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBEncoder;
import com.mongodb.DBObject;
import com.mongodb.InsertOptions;
import com.mongodb.MongoClient;
import com.mongodb.QueryOperators;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;
import com.sobey.jcg.sobeyhive.sidistran.mongo2.Constants.Fields;
import com.sobey.jcg.sobeyhive.sidistran.mongo2.Constants.Values;
import com.sobey.jcg.sobeyhive.sidistran.mongo2.ex.SidistranMongoCuccrentException;

/**
 * Created by WX on 2015/12/29.
 *
 * 由于DB被代理了{@see com.sobey.jcg.sobeyhive.sidistran.mongo.SidistranMongoDB}。
 * 并且，其中的executor也被hack了。因此
 * 这里可以大胆的继承。因为executor会被传递到这里
 *
 * TODO:1.有没有更好的办法处理镜像？
 * TODO:2.删除逻辑还有待优化--如果tx1<tx2，那么，tx2的删除是允许覆盖tx1的处理的。目前是冲突报错
 */
public class SidistranDBCollection extends DBCollection {
    private String stat_f = Fields.ADDITIONAL_BODY+"."+Fields.STAT_FILED_NAME;
    private String txid_f = Fields.ADDITIONAL_BODY+"."+Fields.TXID_FILED_NAME;
    private String u_by_f = Fields.ADDITIONAL_BODY+"."+Fields.UPDATEBY_TXID_NAME;
    private String u_From_f = Fields.ADDITIONAL_BODY+"."+Fields.UPDATE_FROM_NAME;
    private String db_time_f = Fields.ADDITIONAL_BODY+"."+Fields.GARBAGE_TIME_NAME;

    private boolean indexEnsured;
    private MongoReadWriteLock readWriteLock;

    SidistranDBCollection(DB database, String name) {
        super(database, name);
        if(!indexEnsured) {
            assureIndex();
            indexEnsured = true;
        }
        readWriteLock = MongoReadWriteLock.getLock((MongoClient) database.getMongo());
    }

    private void assureIndex(){
        boolean stat_f_i = false;
        boolean txid_f_i = false;
        boolean u_by_f_i = false;
        boolean u_From_f_i = false;
        boolean db_time_f_i = false;

        List<DBObject> indices = this.getIndexInfo();
        for(DBObject dbObject : indices){
            DBObject key = (DBObject)dbObject.get("key");
            if(key.get(stat_f)!=null&&key.get(stat_f).toString().equals("1")){
                stat_f_i = true;
            }
            if(key.get(txid_f)!=null&&key.get(txid_f).toString().equals("1")){
                txid_f_i = true;
            }
            if(key.get(u_by_f)!=null&&key.get(u_by_f).toString().equals("1")){
                u_by_f_i = true;
            }
            if(key.get(u_From_f)!=null&&key.get(u_From_f).toString().equals("1")){
                u_From_f_i = true;
            }
            if(key.get(db_time_f)!=null&&key.get(db_time_f).toString().equals("1")){
                db_time_f_i = true;
            }
        }

        if(!stat_f_i){
            this.createIndex(
                new BasicDBObject(stat_f, 1),
                new BasicDBObject("partialFilterExpression", new BasicDBObject(u_by_f, new BasicDBObject(QueryOperators.EXISTS, false))
                    .append(u_From_f, new BasicDBObject("$exists", false)))
            );
        }

        if(!txid_f_i){
            this.createIndex(
                new BasicDBObject(txid_f, 1),
                new BasicDBObject("partialFilterExpression", new BasicDBObject(stat_f, new BasicDBObject(QueryOperators.GT, Values.REMOVE_STAT)
                    .append(QueryOperators.LT, Values.COMMITED_STAT)))
            );
        }

        if(!u_by_f_i){
            this.createIndex(new BasicDBObject(u_by_f, 1));
        }

        if(!u_From_f_i){
            this.createIndex(new BasicDBObject(u_From_f, 1));
        }

        if(!db_time_f_i){
            this.createIndex(new BasicDBObject(db_time_f, 1));
        }
    }

    //region ----------------insert-----------------------
    /**
     * 所有的insert都会走到这个方法，因此覆盖此方法
     */
    @Override
    public WriteResult insert(final List<? extends DBObject> documents, final InsertOptions insertOptions) {
        if(MongoTanscationManager.hasTransaction()){
            long txid = MongoTanscationManager.txid_AtThisContext();
            /**
             * 如果是在sidistran环境下，则通过版本来控制可见性 {@see this.find()}
             * 通过增加一个版本字段来控制可见性
             */
            for (DBObject cur : documents) {
                if (cur.get(Fields.ADDITIONAL_BODY) == null) {
                    DBObject body = new BasicDBObject();
                    //stat用于控制可见性
                    body.put(Fields.STAT_FILED_NAME, Values.INSERT_NEW_STAT);
                    //txid用于控制事务范围
                    body.put(Fields.TXID_FILED_NAME, txid);
                    cur.put(Fields.ADDITIONAL_BODY, body);
                }
            }
            MongoTanscationManager.current().addTransactionTargetIfAbsent(this);
        }
        return super.insert(documents, insertOptions);
    }
    //endregion


    //region ----------------remove-----------------------
    /**
     * 所有的remove都会走到这个方法，因此覆盖此方法
     *
     * 删除变成逻辑删除
     */
    @Override
    public WriteResult remove(final DBObject query, final WriteConcern writeConcern) {
        return this.remove(query, writeConcern, null);
    }
    @Override
    public WriteResult remove(DBObject query) {
        return this.remove(query, WriteConcern.ACKNOWLEDGED);
    }
    @Override
    public WriteResult remove(DBObject query, WriteConcern writeConcern, DBEncoder encoder) {
        if(MongoTanscationManager.hasTransaction()){
            //在sidistran环境下，删除变成逻辑删除
            //这里一定要使用this的，因为是版本事务控制
            //query会在update中处理
            WriteResult result = this.update(query, new BasicDBObject(), false, true,
                writeConcern, encoder, UpdateType.REMOVE);
            return result;
        }else {
            //正常模式下，不能看到sidistran的数据
            commonQueryAdapt(query);
            return super.remove(query, writeConcern, encoder);
        }
    }
    @Override
    public DBObject findAndRemove(DBObject query) {
        if(true){
            throw new UnsupportedOperationException("还没有完成");
        }
        try {
            readWriteLock.readLock().lock();
            //在sidistran环境下，删除变成逻辑删除。
            if (MongoTanscationManager.hasTransaction()) {
                sidistranQueryAdapt(query);
                DBObject update = new BasicDBObject();// removeAdapt();
                return super.findAndModify(query, update);
            } else {
                //正常模式下，不能看到sidistran的数据
                commonQueryAdapt(query);
                return super.findAndRemove(query);
            }
        }finally {
            readWriteLock.readLock().unlock();
        }
    }
    //endregion


    //region ----------------count-----------------------
    /**
     * 同上
     */
    @Override
    public long getCount(final DBObject query, DBObject projection, final long limit, final long skip,
                         final ReadPreference readPreference) {
        if (MongoTanscationManager.hasTransaction()) {
            sidistranQueryAdapt(query);
        } else {
            //正常模式下，不能看到sidistran的数据
            commonQueryAdapt(query);
        }
        if(projection==null){
            projection = new BasicDBObject();
        }
        projectionAdapt(projection);

        try {
            readWriteLock.readLock().lock();
            return super.getCount(query, projection, limit, skip, readPreference);
        }finally {
            readWriteLock.readLock().unlock();
        }
    }
    //endregion


    //region ----------------find-----------------------
    /**
     * 查询方法，都需要覆盖
     */
    @Override
    public DBCursor find(final DBObject query) {
        return this.find(query, null);
    }
    @Override
    public DBCursor find(final DBObject query, DBObject projection) {
        if(MongoTanscationManager.hasTransaction()) {
            sidistranQueryAdapt(query);
        }else{
            //正常模式下，不能看到sidistran的数据
            commonQueryAdapt(query);
        }
        if(projection==null){
            projection = new BasicDBObject();
        }
        projectionAdapt(projection);
        //DBDBCursor是一个服务端远程指针，实际上是用Collection的find方法
        //最终通过Excecutor的connection和服务端通讯取得next
        //因此，这里不能用super的，要new一个，把this传递进去
        try {
            readWriteLock.readLock().lock();
            DBCursor cursor = new DBCursor(this, query, projection, getReadPreference());
            return cursor;
        }finally {
            readWriteLock.readLock().unlock();
        }
    }
    @Override
    public DBObject findOne(final DBObject query, DBObject projection, final DBObject sort,
                            final ReadPreference readPreference) {
        if(MongoTanscationManager.hasTransaction()) {
            sidistranQueryAdapt(query);
        }else{
            //正常模式下，不能看到sidistran的数据
            commonQueryAdapt(query);
        }
        if(projection==null){
            projection = new BasicDBObject();
        }
        projectionAdapt(projection);
        try {
            readWriteLock.readLock().lock();
            return super.findOne(query, projection, sort, readPreference);
        }finally {
            readWriteLock.readLock().unlock();
        }
    }
    @Override
    public List distinct(final String fieldName, final DBObject query, final ReadPreference readPreference) {
        if(MongoTanscationManager.hasTransaction()) {
            sidistranQueryAdapt(query);
        }else{
            //正常模式下，不能看到sidistran的数据
            commonQueryAdapt(query);
        }
        try {
            readWriteLock.readLock().lock();
            return super.distinct(fieldName, query, readPreference);
        }finally {
            readWriteLock.readLock().unlock();
        }
    }

    //endregion

    //region ----------------update-----------------------
    /**
     * update的处理都会走到这里
     */
    @Override
    public WriteResult update(DBObject query, DBObject update, final boolean upsert, final boolean multi,
                              final WriteConcern aWriteConcern, final DBEncoder encoder) {
        return this.update(query, update, upsert, multi, aWriteConcern, encoder, UpdateType.UPDATE);
    }

    private WriteResult update(DBObject query, DBObject update, final boolean upsert, final boolean multi,
                              final WriteConcern aWriteConcern, final DBEncoder encoder, UpdateType updateType) {
        try {
            readWriteLock.readLock().lock();
            if(query instanceof CommitDBQuery){
                return super.update(query, update, upsert, multi, aWriteConcern, encoder);
            }else if (MongoTanscationManager.hasTransaction()) {
                if (upsert) {
                    throw new UnsupportedOperationException("Sidisitran环境下不能使用upsert=true");
                }

                long txid = MongoTanscationManager.txid_AtThisContext();

                //--------------warning!!!效率影响问题开始------------------
                //这里有一个【非可靠】的乐观锁处理：
                //1、先读取事务可见性中的待处理（所谓待处理就是值得当前事务中看到的stat=2的数据）数据，获取countA
                //2、update这些数据，设置这些数据为当前事务所处理
                //   update的条件为：__s_u_txid不存在
                //3、由于[幂等性]，1和2返回的count应该一致。

                //1.当前事务可见的“待处理”数据，也就是 当前事务.可见的.那些已提交的数据
                DBObject query_4_common = new BasicDBObject(query.toMap());//clone一个出来
                snapshotQueryOnSidistranUpdate(query_4_common);//这个查询保证[幂等性]

                //1.快照获取。复制一份数据版本的快照出来，这些数据指的是query覆盖的普通数据
                DBCursor cursor = super.find(query_4_common);//调用super的查询，原汁原味
                int findCount = cursor.count();
                if (findCount > 0) {
                    List<DBObject> list = new ArrayList<DBObject>();
                    while (cursor.hasNext()) {
                        DBObject dbObject = cursor.next();
                        //将update的数据复制一份出来进行处理
                        if (!dbObject.containsField(Fields.ADDITIONAL_BODY)) {
                            throw new UnsupportedOperationException("无法处理数据，因为没有" + Fields.ADDITIONAL_BODY);
                        }

                        DBObject body = (DBObject) dbObject.get(Fields.ADDITIONAL_BODY);
                        if (body.get(Fields.UPDATEBY_TXID_NAME) != null) {
                            long locker = ((Long) body.get(Fields.UPDATEBY_TXID_NAME)).longValue();
                            if (locker != txid) {
                                try {
                                    throw new SidistranMongoCuccrentException("could not serialize access due to concurrent update." +
                                        " cur.txid=" + txid + " , _id=" + dbObject.get("_id") + ", locker=" + body.get(Fields.UPDATEBY_TXID_NAME));
                                } finally {
                                    proRollback(encoder);
                                }
                            }
                        }

                        body = new BasicDBObject();
                        int stat = updateType == UpdateType.UPDATE ? Values.INSERT_NEW_STAT : Values.REMOVE_STAT;
                        dbObject.put(Fields.ADDITIONAL_BODY, body);
                        body.put(Fields.TXID_FILED_NAME, txid);
                        body.put(Fields.STAT_FILED_NAME, stat);
                        body.put(Fields.UPDATE_FROM_NAME, dbObject.get("_id"));
                        dbObject.removeField("_id");
                        list.add(dbObject);
                    }
                    //复制快照数据
                    //TODO：也许有一个更好的办法来处理快照。可以试试setOnInsert
                    //@see https://docs.mongodb.org/manual/reference/operator/update/setOnInsert/#up._S_setOnInsert
                    super.insert(list, new InsertOptions().writeConcern(aWriteConcern).dbEncoder(encoder));

                    //然后，对原始数据增加当前事务标识。
                    //这里需要处理的，只能是被update的原始数据
                    DBObject update_4_common = this.getOriDataUpdateObj();//增加事务标识
                    query_4_common = new BasicDBObject(query.toMap());//clone一个出来
                    lockQueryOnSidistranUpdate(query_4_common);
                    //query_4_common标识的是common数据
                    WriteResult r = super.update(query_4_common, update_4_common, upsert, multi, aWriteConcern, encoder);
                    if (r.getN() != findCount) {
                        try {
                            throw new SidistranMongoCuccrentException("could not serialize access due to concurrent update");
                        } finally {
                            proRollback(encoder);
                        }
                    }
                }

                //对复制出来的数据，当前事务中的insert数据、update数据，进行真正的更新
                sidistranQueryAdapt(query);
                //这里需要的是对remove的处理。如果是当前事务中的insert数据、update数据，直接用原始请求更新即可
                if (updateType == UpdateType.REMOVE) {
                    update = this.getRemoveUpdateObj();
                }
                MongoTanscationManager.current().addTransactionTargetIfAbsent(this);
                return super.update(query, update, upsert, multi, aWriteConcern, encoder);
            } else {
                //普通情况下，直接执行
                commonQueryAdapt(query);
                return super.update(query, update, upsert, multi, aWriteConcern, encoder);
            }
        }finally {
            readWriteLock.readLock().unlock();
        }
    }
    //endregion

    //region ----------------aggregate-----------------------
    @Override
    public Cursor aggregate(final List<? extends DBObject> pipeline, final AggregationOptions options,
                            final ReadPreference readPreference) {
        if(MongoTanscationManager.hasTransaction()){
            pipeLineAdapt(pipeline, true);
        }else{
            pipeLineAdapt(pipeline, false);
        }
        try {
            readWriteLock.readLock().lock();
            return super.aggregate(pipeline, options, readPreference);
        }finally {
            readWriteLock.readLock().unlock();
        }
    }

    @Override
    public AggregationOutput aggregate(final List<? extends DBObject> pipeline, final ReadPreference readPreference) {
        if(MongoTanscationManager.hasTransaction()){
            pipeLineAdapt(pipeline, true);
        }else{
            pipeLineAdapt(pipeline, false);
        }
        try {
            readWriteLock.readLock().lock();
            return super.aggregate(pipeline, readPreference);
        }finally {
            readWriteLock.readLock().unlock();
        }
    }
    //endregion

    //region =--===================查询适配方法，史无前例的华丽分割线，老子弄的--------===========
    /**
     * 在非sidistran的情况下，普通查询的内容：
     * 就是那些已经提交过的数据
     *
     * $and: [
     *       {
     *          __s_.__s_stat: 2, __s_._s_c_time:{$exists:false}
     *       }
     * ]
     * @param query
     */
    private void commonQueryAdapt(DBObject query){
        DBObject con = new BasicDBObject(stat_f, Values.COMMITED_STAT)
            .append(db_time_f, new BasicDBObject("$exists", false));
        queryAdapt(query, con);
    }

    /**
     * 在sidistran环境下的query【实现可重复读】
     * 能够看到的数据是：
     * 事务启动那个时刻的已提交数据、当前事务中insert和update的数据
     * 但是不能看到被update复制的原始数据
     *
     * 1、事务启动时刻的已提交数据
     *  {__s_.__s_stat: 2, __s_.__s_c_txid:{$lte: cur.txid}}
     * 2、对于普通数据和当前事务中的insert、update有：
     *  D:-1, C:0, U:1, OK:2
     *  因此，X=2, 就只有普通数据
     *  同时，当前事务中，并且不为-1和2的，就是当前事务中的insert和update的数据
     *  当前事务中不会存在OK的数据
     *  { __s_.__s_c_txid: cur.txid, __s_.__s_stat:{$gt:-1, $lt:2}}//当前事务中的insert和update数据
     *
     *
     * $and: [
     *        {
     *          $or:[
     *            //当前事务或上一个事务创建的，且未修改的
     *            {__s_.__s_stat: 2, __s_.__s_c_txid:{$lte: cur.txid},__s_.s_u_txid:{$exists:false},__s_._s_c_time:{$exists:false}},
     *            //当前事务或上一个事务创建后，被后续事务修改
     *            {__s_.__s_stat: 2, __s_.__s_c_txid:{$lte: cur.txid},__s_.s_u_txid:{$gt:cur.txid},__s_._s_c_time:{$gt:cur.time}},
     *            //由前面的事务创建，被前面的事务修改的
     *            {__s_.__s_stat: 2, __s_.__s_c_txid:{$lt: cur.txid},__s_.s_u_txid:{$lt: cur.txid},__s_._s_c_time:{$gt:cur.time}},
     *            //当前事务中的insert和update数据
     *            {__s_.__s_c_txid: cur.txid, __s_.__s_stat:0}
     *          ]
     *        }
     * ]
     *
     * __s_u_txid的两个“不等于”的条件，决定了被txid处理的数据，每一次查询不会被查出来
     * 保证了幂等性
     *
     * @param query
     */
    private void sidistranQueryAdapt(DBObject query){
        long txid = MongoTanscationManager.txid_AtThisContext();
        long time = MongoTanscationManager.current().getTx_time();

//        String updateByField = Fields.ADDITIONAL_BODY+"."+Fields.UPDATEBY_TXID_NAME
//            +".txid_"+txid;

        DBObject con = new BasicDBObject()
            .append("$or", new BasicDBObject[]{
                new BasicDBObject(stat_f, Values.COMMITED_STAT)
                    .append(txid_f, new BasicDBObject(QueryOperators.LTE, txid))
                    .append(u_by_f, new BasicDBObject(QueryOperators.EXISTS, false))
                    .append(db_time_f, new BasicDBObject(QueryOperators.EXISTS, false)),
                new BasicDBObject(stat_f, Values.COMMITED_STAT)
                    .append(txid_f, new BasicDBObject(QueryOperators.LTE, txid))
                    .append(u_by_f, new BasicDBObject(QueryOperators.GT, txid))
                    .append(db_time_f, new BasicDBObject(QueryOperators.GT, time)),
                new BasicDBObject(stat_f, Values.COMMITED_STAT)
                    .append(txid_f, new BasicDBObject(QueryOperators.LT, txid))
                    .append(u_by_f, new BasicDBObject(QueryOperators.LT, txid))
                    .append(db_time_f, new BasicDBObject(QueryOperators.GT, time)),
                new BasicDBObject(txid_f, txid)
                    .append(stat_f,
                        Values.INSERT_NEW_STAT
//                      new BasicDBObject(QueryOperators.GT, Values.REMOVE_STAT)
//                      .append(QueryOperators.LT, Values.COMMITED_STAT)
                    )
            });
        queryAdapt(query, con);
    }

    /**
     * update的时候获取快照的查询
     * 这个查询能看到得到是：
     *
     * 在sidistranQueryAdapt的前提下
     * __s_._s_stat=2的数据
     *
     * __s_u_txid的两个“不等于”的条件，决定了被txid处理的数据，每一次查询不会被查出来
     * 保证了幂等性
     *
     * 其实就是：
     *
     *  $and: [
     *        {
     *          __s_.__s_stat: 2，
     *          $or:[
     *            //当前事务或上一个事务创建的，且未修改的
     *            {__s_.__s_c_txid:{$lte: cur.txid},__s_.s_u_txid:{$exists:false},__s_._s_c_time:{$exists:false}},
     *            //当前事务或上一个事务创建后，被后续事务修改，但还没提交的
     *            {__s_.__s_c_txid:{$lte: cur.txid},__s_.s_u_txid:{$gt:cur.txid},__s_._s_c_time:{$gt:cur.time}},
     *            //由前面的事务创建，并且前面的事务还在修改，但还没提交的
     *            {__s_.__s_c_txid:{$lt: cur.txid},__s_.s_u_txid:{$lt: cur.txid},__s_._s_c_time:{$gt:cur.time}},
     *          ]
     *        }
     * ]
     *
     * @param query
     */
    private void snapshotQueryOnSidistranUpdate(DBObject query){
        long txid = MongoTanscationManager.txid_AtThisContext();
        long time = MongoTanscationManager.current().getTx_time();

        DBObject con = new BasicDBObject(stat_f, Values.COMMITED_STAT)
            .append("$or", new BasicDBObject[]{
                new BasicDBObject(txid_f, new BasicDBObject(QueryOperators.LTE, txid))
                    .append(u_by_f, new BasicDBObject(QueryOperators.EXISTS, false))
                    .append(db_time_f, new BasicDBObject(QueryOperators.EXISTS, false)),
                new BasicDBObject(txid_f, new BasicDBObject(QueryOperators.LTE, txid))
                    .append(u_by_f, new BasicDBObject(QueryOperators.GT, txid))
                    .append(db_time_f, new BasicDBObject(QueryOperators.GT, time)),
                new BasicDBObject(txid_f, new BasicDBObject(QueryOperators.LT, txid))
                    .append(u_by_f, new BasicDBObject(QueryOperators.LT, txid))
                    .append(db_time_f, new BasicDBObject(QueryOperators.GT, time))
            });
        queryAdapt(query, con);
    }


    /**
     * update的时候进行乐观锁判定的查询
     * 这个查询能看到得到是：
     * 在sidistranQueryAdapt的前提下
     * __s_._s_stat=2&&__s_.s_u_txid==null的数据
     *
     * 其实就是:
     * 当前事务范围内，还没有__u_txid的数据
     *
     * $and: [
     *        {
     *          __s_.__s_stat: 2, __s_.__s_c_txid:{$lte: cur.txid},__s_.s_u_txid:{$exists:false},__s_._s_c_time:{$exists:false}
     *        }
     * ]
     *
     * @param query
     */
    private void lockQueryOnSidistranUpdate(DBObject query){
        long txid = MongoTanscationManager.txid_AtThisContext();

        DBObject con = new BasicDBObject(stat_f, Values.COMMITED_STAT)
                    .append(txid_f, new BasicDBObject(QueryOperators.LTE, txid))
            .append(u_by_f, new BasicDBObject(QueryOperators.EXISTS, false))
            .append(db_time_f, new BasicDBObject(QueryOperators.EXISTS, false));
        queryAdapt(query, con);
    }


    //主要的逻辑点在于：query的第一层，可能存在一个and，可能存在一个or，也可能两个都不存在。
    private void queryAdapt(DBObject query, DBObject con){
        //这里修改条件，只看query这个json的【最外层】，其他里面不管是否有and还是or，都与此无关。
        if(query.containsField("$and")){
            //最外层没得or，但是有个and
            Object[] con_in_and = (Object[])query.get("$and");
            //看看这个and里面是否已经有一个or
            int i = Arrays.binarySearch(con_in_and, con, conditin_in_and_Finder);
            if(i<0){
                //不存在,则在and里面加一个就行了。
                //否则就说明已经有了
                con_in_and = ArrayUtils.add(con_in_and, con);
                query.put("$and", con_in_and);
            }
        }else{
            //没得or，没得and,那就加一个and到条件冲
            query.put("$and", new DBObject[]{con});
        }
//        System.out.println(query);
    }

    //queryAdapt的辅助处理。主要是处理or。对于每一个or因子，需要增加一个and
    @Deprecated
    private void updateOr(Object[] or, BasicDBObject con){
        for(Object o1 : or){
             if (o1 instanceof Map){
                Map o = (Map)o1;
                if(o.containsKey("$and")){
                    Object[] and = (Object[])o.get("$and");
                    int i = Arrays.binarySearch(and, con, conditin_in_and_Finder);
                    if(i<0) {
                        and = ArrayUtils.add(and, con);
                        o.put("$and", and);
                    }
                }else{
                    ((Map)o1).put("$and", new BasicDBObject[]{con});
                }
            }else if(o1 instanceof BSONObject){
                BSONObject o = (BSONObject)o1;
                if(o.containsField("$and")){
                    Object[] and = (Object[])o.get("$and");
                    int i = Arrays.binarySearch(and, con, conditin_in_and_Finder);
                    if(i<0) {
                        and = ArrayUtils.add(and, con);
                        o.put("$and", and);
                    }
                }else{
                    ((BSONObject)o1).put("$and", new BasicDBObject[]{con});
                }
            }else{
                throw new UnsupportedOperationException(o1.getClass()+"之前还没遇到过");
            }
        }
    }

    /**
     * 处理查询返回结果，过滤掉version控制字段
     * @param projection
     */
    private void projectionAdapt(DBObject projection){
        if(!projection.containsField(Fields.ADDITIONAL_BODY)){
            projection.put(Fields.ADDITIONAL_BODY, false);
        }
    }

    /**
     * 处理aggregate的pipeline
     */
    private void pipeLineAdapt(List<? extends DBObject> pipeline, boolean sidistran){
        //由于pipeline是一个顺序管道过滤。
        //因此看看最开始是否有一个match。如果没有，则增加。如果有，则修改
        if(pipeline==null) throw new IllegalArgumentException("pipeline不能为空");

        DBObject match = pipeline.get(0);
        if(match.get("$match")!=null){
            //说明第一个就是match
            DBObject query = (DBObject)match.get("$match");
            if(sidistran){
                sidistranQueryAdapt(query);
            }else{
                commonQueryAdapt(query);
            }
        }else{
            //第一个不是match
            DBObject query = new BasicDBObject();
            if(sidistran){
                sidistranQueryAdapt(query);
            }else{
                commonQueryAdapt(query);
            }
            query = new BasicDBObject("$match", query);

            //反射执行 pipeline.add(0, query);
            //由于list是一个? extends A泛型，属于只读泛型，list写入的时候，？不确定，而List是一个强类型数组
            //因此add编译不过。
            try {
                MethodUtils.invokeMethod(pipeline, "add", new Object[]{0, query});
            }catch (Exception e){
                if(e instanceof RuntimeException){
                    throw (RuntimeException)e;
                }
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 对原始数据(stat=2)进行update处理
     * 1.增加当前事务标识，标识事务在update这个数据
     * 2.增加历史处理记录
     */
    private DBObject getOriDataUpdateObj(){
        long txid = MongoTanscationManager.txid_AtThisContext();
        DBObject update = new BasicDBObject();

        update.put("$set", new BasicDBObject(u_by_f, txid));

        return update;
    }

    /**
     */
    private DBObject getRemoveUpdateObj(){
        BasicDBObject update = new BasicDBObject("$set",
            new BasicDBObject(stat_f, Values.REMOVE_STAT));

        return update;
    }

    /**
     * 在失败的时候，释放当前事务占用的资源
     * 回滚的时候，这部分数据就不需要处理了
     */
    private void proRollback(DBEncoder dbEncoder){
        long txid = MongoTanscationManager.txid_AtThisContext();
        //释放所有的资源
        //1.将占用数据，解除
        super.update(new BasicDBObject(u_by_f, txid),
            new BasicDBObject("$unset", new BasicDBObject(u_by_f, "")),
            false, true, WriteConcern.ACKNOWLEDGED, dbEncoder
        );
        //2.标记所有的临时数据为需要被删除
        super.update(
            new BasicDBObject(txid_f, txid).append(stat_f, new BasicDBObject("$ne", Values.COMMITED_STAT)),
            new BasicDBObject("$set", new BasicDBObject(stat_f, Values.NEED_TO_REMOVE)),
            false, true, WriteConcern.ACKNOWLEDGED, dbEncoder
        );
    }

    //看看在一组and的条件中，是否已经包含o2代表的条件对象
    private static final Comparator conditin_in_and_Finder = new  Comparator<Object>() {
        public int compare(Object o1, Object o2) {
            if((o1 instanceof BasicDBObject)&&(o2 instanceof BasicDBObject)
                &&o1.equals(o2)) {
                return 0;
            }
            return -1;
        }
    };


    private static enum UpdateType{
        REMOVE,
        UPDATE
    }
    //endregion
}