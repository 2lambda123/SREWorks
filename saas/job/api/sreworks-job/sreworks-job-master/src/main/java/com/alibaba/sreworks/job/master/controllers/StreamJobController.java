package com.alibaba.sreworks.job.master.controllers;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.sreworks.job.master.domain.DTO.*;
import com.alibaba.sreworks.job.master.params.*;
import com.alibaba.sreworks.job.master.services.StreamJobBlockService;
import com.alibaba.sreworks.job.master.services.StreamJobService;
import com.alibaba.sreworks.job.master.services.VvpService;
import com.alibaba.sreworks.job.utils.JsonUtil;
import com.alibaba.sreworks.job.utils.YamlUtil;
import com.alibaba.tesla.common.base.TeslaBaseResult;
import com.alibaba.tesla.web.controller.BaseController;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.alibaba.sreworks.job.utils.PageUtil.pageable;

/**
 * @author jinghua.yjh
 */
@Slf4j
@RestController
@RequestMapping("/stream-job")
public class StreamJobController extends BaseController {

    @Autowired
    StreamJobService streamJobService;

    @Autowired
    StreamJobBlockService streamJobBlockService;

    @Autowired
    VvpService vvpService;

    @RequestMapping(value = "create", method = RequestMethod.POST)
    public TeslaBaseResult create(@RequestBody StreamJobCreateParam param) throws Exception {
        param.setCreator(getUserEmployeeId());
        param.setOperator(getUserEmployeeId());
        if (param.getTags() == null) {
            param.setTags(new JSONArray());
        }
        if (param.getDescription() == null) {
            param.setDescription("");
        }
        if (param.getOptions() == null) {
            param.setOptions(new JSONObject());
        }
        return buildSucceedResult(streamJobService.create(param));
    }

    @RequestMapping(value = "runtimes", method = RequestMethod.GET)
    public TeslaBaseResult runtimes(Integer page, Integer pageSize) throws Exception {
        Page<SreworksStreamJobRuntimeDTO> runtimes = streamJobService.runtimes(pageable(page, pageSize));
        return buildSucceedResult(JsonUtil.map(
                "page", runtimes.getNumber() + 1,
                "pageSize", runtimes.getSize(),
                "total", runtimes.getTotalElements(),
                "items", runtimes.getContent()
        ));
    }

    @RequestMapping(value = "runtime/{id}", method = RequestMethod.GET)
    public TeslaBaseResult runtimeGet(@PathVariable("id") Long runtimeId) throws Exception {
        SreworksStreamJobRuntimeDTO runtime = streamJobService.runtimeGetById(runtimeId);
        return buildSucceedResult(runtime);
    }

    @RequestMapping(value = "runtime", method = RequestMethod.POST)
    public TeslaBaseResult addRuntime(@RequestBody StreamJobRuntimeCreateParam param) throws Exception {
        SreworksStreamJobRuntimeDTO runtime = streamJobService.addRuntime(param);
        return buildSucceedResult(runtime);
    }

    @RequestMapping(value = "runtime/preview", method = RequestMethod.POST)
    public TeslaBaseResult runtimePreview(@RequestBody StreamJobRuntimeCreateParam param) throws Exception {
        JSONObject deployment = streamJobService.generateDeployment(
            StreamJobDeploymentParam.builder()
            .entryClass(param.getSettings().getString("entryClass"))
            .flinkImage(param.getSettings().getString("flinkImage"))
            .jarUri(param.getSettings().getString("jarUri"))
            .name(param.getName())
            .build()
        );

        return buildSucceedResult(YamlUtil.jsonObjectToYaml(deployment));
    }
    @RequestMapping(value = "artifacts", method = RequestMethod.GET)
    public TeslaBaseResult artifacts(StreamJobArtifactsParam param) throws Exception {
        JSONObject artifacts = vvpService.listArtifacts();
        JSONArray artifactList = artifacts.getJSONArray("artifacts");

        artifactList.sort((o1, o2) -> {
            String name1 = ((JSONObject) o1).getString("createTime");
            String name2 = ((JSONObject) o2).getString("createTime");
            return name1.compareTo(name2);
        });

        Collections.reverse(artifactList);

        if(param.getIsRespOptions()){
            return buildSucceedResult(JsonUtil.map(
                "options", artifactList
            ));
        }else{
            return buildSucceedResult(artifactList);
        }
    }
    @RequestMapping(value = "{id}/delete", method = RequestMethod.DELETE)
    public TeslaBaseResult delete(@PathVariable("id") Long streamJobId) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        vvpService.deleteDeployment(streamJobService.getDeploymentName(streamJobId));
        JSONObject currentDeployment = vvpService.getDeployment(streamJobService.getDeploymentName(streamJobId));
        if (currentDeployment.getString("statusCode") != null && currentDeployment.getLong("statusCode") == 404) {
            streamJobBlockService.deleteByStreamJobId(streamJobId);
            streamJobService.delete(streamJobId);
            return buildSucceedResult(job);
        } else {
            return buildClientErrorResult("vvp job still exist");
        }

    }

    @RequestMapping(value = "{id}/settings", method = RequestMethod.PUT)
    public TeslaBaseResult updateSettings(@PathVariable("id") Long streamJobId, @RequestBody StreamJobSourceUpdateSettingsParam param) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        param.setOperator(getUserEmployeeId());
        return buildSucceedResult(streamJobService.updateSettings(job, param.getSettings()));
    }

    @RequestMapping(value = "{id}/template", method = RequestMethod.PUT)
    public TeslaBaseResult updateTemplate(@PathVariable("id") Long streamJobId, @RequestBody StreamJobSourceUpdateTemplateParam param) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        param.setOperator(getUserEmployeeId());
        return buildSucceedResult(streamJobService.updateTemplate(job, param.getTemplateContent()));
    }

    @RequestMapping(value = "{id}/source", method = RequestMethod.POST)
    public TeslaBaseResult addSource(@PathVariable("id") Long streamJobId, @RequestBody StreamJobSourceCreateParam param) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        param.setCreator(getUserEmployeeId());
        param.setOperator(getUserEmployeeId());
        return buildSucceedResult(streamJobBlockService.addSource(streamJobId, job.getAppId(), param));
    }

    @RequestMapping(value = "{id}/source/{blockId}", method = RequestMethod.PUT)
    public TeslaBaseResult updateSource(
            @PathVariable("id") Long streamJobId,
            @PathVariable("blockId") Long blockId,
            @RequestBody StreamJobSourceCreateParam param
    ) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        SreworksStreamJobSourceDTO source = streamJobBlockService.getSource(blockId);
        source = streamJobBlockService.updateSource(source, param);
        return buildSucceedResult(source);
    }

    @RequestMapping(value = "{id}/python/{blockId}", method = RequestMethod.PUT)
    public TeslaBaseResult updatePython(
            @PathVariable("id") Long streamJobId,
            @PathVariable("blockId") Long blockId,
            @RequestBody StreamJobPythonCreateParam param
    ) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        SreworksStreamJobPythonDTO python = streamJobBlockService.getPython(blockId);
        return buildSucceedResult(streamJobBlockService.updatePython(python, param));
    }

    @RequestMapping(value = "{id}/sink/{blockId}", method = RequestMethod.PUT)
    public TeslaBaseResult updateSink(
            @PathVariable("id") Long streamJobId,
            @PathVariable("blockId") Long blockId,
            @RequestBody StreamJobSinkCreateParam param
    ) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        SreworksStreamJobSinkDTO sink = streamJobBlockService.getSink(blockId);
        return buildSucceedResult(streamJobBlockService.updateSink(sink, param));
    }

    @RequestMapping(value = "{id}/block/{blockId}", method = RequestMethod.DELETE)
    public TeslaBaseResult deleteBlock(@PathVariable("id") Long streamJobId, @PathVariable("blockId") Long streamJobBlockId) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        streamJobBlockService.delete(streamJobBlockId);
        return buildSucceedResult(JsonUtil.map(
            "streamJobId", streamJobId,
                "blockId", streamJobBlockId
        ));
    }

    @RequestMapping(value = "{id}/sink", method = RequestMethod.POST)
    public TeslaBaseResult addSink(@PathVariable("id") Long streamJobId, @RequestBody StreamJobSinkCreateParam param) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        param.setCreator(getUserEmployeeId());
        param.setOperator(getUserEmployeeId());
        return buildSucceedResult(streamJobBlockService.addSink(streamJobId, job.getAppId(), param));
    }

    @RequestMapping(value = "{id}/python", method = RequestMethod.POST)
    public TeslaBaseResult addPython(@PathVariable("id") Long streamJobId, @RequestBody StreamJobPythonCreateParam param) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        param.setCreator(getUserEmployeeId());
        param.setOperator(getUserEmployeeId());
        return buildSucceedResult(streamJobBlockService.addPython(streamJobId, job.getAppId(), param));
    }

    @RequestMapping(value = "{id}/python/template", method = RequestMethod.GET)
    public TeslaBaseResult pythonTemplate(@PathVariable("id") Long streamJobId) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        return buildSucceedResult(streamJobService.pythonTemplate(streamJobId));
    }

    @RequestMapping(value = "{id}/blocks", method = RequestMethod.GET)
    public TeslaBaseResult getBlocks(@PathVariable("id") Long streamJobId) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        return buildSucceedResult(streamJobBlockService.listByStreamJobId(streamJobId));
    }

    @RequestMapping(value = "{id}/preview", method = RequestMethod.GET)
    public TeslaBaseResult getPreview(@PathVariable("id") Long streamJobId) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        return buildSucceedResult(JsonUtil.map(
        "script", streamJobService.generateScript(streamJobId, job.getOptions().getString("template")),
                "deployment", streamJobService.generateDeploymentByStreamJob(job)
        ));
    }

    @RequestMapping(value = "{id}/export", method = RequestMethod.GET)
    public ResponseEntity<Resource> export(@PathVariable("id") Long streamJobId) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        HttpHeaders header = new HttpHeaders();
        if(job == null){
            return ResponseEntity.notFound().build();
        }
        File jobFile = streamJobService.exportFile(job);
        InputStreamResource resource = new InputStreamResource(new FileInputStream(jobFile));
        header.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + streamJobService.getDeploymentName(streamJobId) + ".zip");
        return ResponseEntity.ok()
                .headers(header)
                .contentLength(jobFile.length())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @RequestMapping(value = "import", method = RequestMethod.POST)
    public TeslaBaseResult jobImport(@ModelAttribute StreamJobCreateParam param) throws Exception {

        param.setCreator(getUserEmployeeId());
        param.setOperator(getUserEmployeeId());
        if (param.getTags() == null) {
            param.setTags(new JSONArray());
        }
        if (param.getDescription() == null) {
            param.setDescription("");
        }
        byte[] bytes = param.getFile().getBytes();
        File zipFile = Files.createTempFile("stream-job", ".zip").toFile();
        Files.write(zipFile.toPath(), bytes);
        log.info("job import => {}", param);
        SreworksStreamJobDTO job = streamJobService.importFile(param, zipFile);
        return buildSucceedResult(job);
    }

    @RequestMapping(value = "{id}/operate/start", method = RequestMethod.POST)
    public TeslaBaseResult operateStart(@PathVariable("id") Long streamJobId) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }

        // 上传脚本
        JSONObject result = vvpService.uploadArtifact(
                streamJobService.getScriptName(streamJobId),
                streamJobService.generateScript(streamJobId, job.getOptions().getString("template"))
        );

        JSONObject deployment = streamJobService.generateDeploymentByStreamJob(job);

        // 启动flink job
        JSONObject response;
        JSONObject currentDeployment = vvpService.getDeployment(streamJobService.getDeploymentName(streamJobId));
        if (currentDeployment.getString("statusCode") != null && currentDeployment.getLong("statusCode") == 404) {
            response = vvpService.createDeployment(
                    deployment.getJSONObject("metadata"),
                    deployment.getJSONObject("spec")
            );
        }else{
            response = vvpService.replaceDeployment(streamJobService.getDeploymentName(streamJobId), deployment);
        }
        if (response.getJSONObject("spec") != null && StringUtils.equals(response.getJSONObject("spec").getString("state"), "RUNNING")) {
            streamJobService.updateStatus(job, "RUNNING");
        }
        return buildSucceedResult(response);
    }

    @RequestMapping(value = "{id}/operate/stop", method = RequestMethod.PUT)
    public TeslaBaseResult operateStop(@PathVariable("id") Long streamJobId) throws Exception {
        SreworksStreamJobDTO job = streamJobService.get(streamJobId);
        if(job == null){
            return buildClientErrorResult("streamJob not found");
        }
        // 取消部署
        JSONObject response = vvpService.cancelDeployment(streamJobService.getDeploymentName(streamJobId));
        if(response.getJSONObject("spec") != null && StringUtils.equals(response.getJSONObject("spec").getString("state"), "CANCELLED")){
            streamJobService.updateStatus(job, "CANCELLED");
        }
        return buildSucceedResult(response);
    }

    @RequestMapping(value = "getByName/{name}", method = RequestMethod.GET)
    public TeslaBaseResult getByName(@PathVariable("name") String name) throws Exception {
        SreworksStreamJobDTO job = streamJobService.getByName(name);
        if(job.getId() == null){
            return buildResult(404, "stream job not found", null);
        }
        return buildSucceedResult(job);
    }

    @RequestMapping(value = "gets", method = RequestMethod.GET)
    public TeslaBaseResult gets(Integer page, Integer pageSize) throws Exception {
        if (page == null) {
            page = 1;
        }
        if (pageSize == null) {
           pageSize = 10;
        }
        page = page - 1;
        Pageable pageable = PageRequest.of(page, pageSize, Sort.by("id").descending());
        Page<SreworksStreamJobDTO> jobList = streamJobService.gets(pageable);
        JSONArray jobItems = JSON.parseArray(JSON.toJSONString(jobList.getContent()));

        for (int i = 0; i < jobItems.size(); i++) {
            JSONObject jobObject = jobItems.getJSONObject(i);
            jobObject.put("vvp-flink-dashboard-link", "javascript:void(0)");
            jobObject.put("vvp-flink-dashboard-link-target", "_self");
            if(StringUtils.equals(jobObject.getString("status"), "RUNNING")){
                JSONObject deployment = vvpService.getDeployment(streamJobService.getDeploymentName(jobObject.getLong("id")));
                jobObject.put("runtime-deployment", deployment);
                boolean jobIdExists = deployment.containsKey("status")
                        && deployment.getJSONObject("status").containsKey("running")
                        && deployment.getJSONObject("status").getJSONObject("running").containsKey("jobId");
                if(jobIdExists){
                    jobObject.put("vvp-flink-dashboard-link-target", "_blank");
                    jobObject.put(
                            "vvp-flink-dashboard-link",
                            "/gateway/vvp-flink-dashboard/"
                            +  deployment.getJSONObject("status").getJSONObject("running").getString("jobId") + "/"
                    );
                    jobObject.put("statusDisplay", "运行中");
                    jobObject.put("statusTip", "点击查看Flink作业");
                } else {
                    String errorMessage = vvpService.checkErrorEventByDeploymentId(
                            deployment.getJSONObject("metadata").getString("id"),
                            deployment.getJSONObject("metadata").getString("modifiedAt")
                    );
                    if(StringUtils.isEmpty(errorMessage)){
                        jobObject.put("statusDisplay", "启动中");
                        jobObject.put("statusTip", "启动中");
                    }else{
                        jobObject.put("statusDisplay", "启动中(异常)");
                        jobObject.put("statusTip", errorMessage);
                    }
                }
            }else{
                jobObject.put("statusDisplay", "未启动");
                jobObject.put("statusTip", "未启动");
            }
        }

        return buildSucceedResult(JsonUtil.map(
        "page", jobList.getNumber() + 1,
            "pageSize", jobList.getSize(),
            "total", jobList.getTotalElements(),
            "items", jobItems
        ));

    }

    @RequestMapping(value = "connectors", method = RequestMethod.GET)
    public TeslaBaseResult getConnectors(StreamJobListConnectorParam param) throws Exception {
        List<FlinkConnectorDTO> connectors = vvpService.listConnector(param.getSource(), param.getSink(), param.getType());
        return buildSucceedResult(connectors);
    }

    @RequestMapping(value = "connector/resource/{connectorName}/{fileName}", method = RequestMethod.GET)
    public ResponseEntity<Resource> getConnectorResource(
            @PathVariable("connectorName") String connectorName,
            @PathVariable("fileName") String fileName
    ) throws Exception {
        String path = "/app/vvp/connectors/" + connectorName + "/" + fileName;

        File file = new File(path);

        InputStreamResource resource = new InputStreamResource(new FileInputStream(file));
        HttpHeaders header = new HttpHeaders();
        header.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName);
        return ResponseEntity.ok()
                .headers(header)
                .contentLength(file.length())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @RequestMapping(value = "connector/properties", method = RequestMethod.GET)
    public TeslaBaseResult getConnectorProperties(String type, String columns) throws Exception {
        if(StringUtils.isEmpty(type)) {
            return buildSucceedResult(null);
        }
        FlinkConnectorDTO connector = vvpService.getConnector(type);
        if (connector == null) {
            return buildSucceedResult(null);
        }

        // 初始化包含 # 和不包含 # 的 JSONArray 数组
        JSONArray propertiesWithSharp = new JSONArray();
        JSONArray properties = new JSONArray();

        for (int i = 0; i < connector.getProperties().size(); i++) {
            JSONObject obj = connector.getProperties().getJSONObject(i);
            if (obj.getString("key").contains("#")) {
                propertiesWithSharp.add(obj);
            } else {
                obj.put("label", obj.getString("key"));
                obj.put("value", obj.getString("key"));
                if (
                        StringUtils.equals("", obj.getString("defaultValue")) &&
                                obj.getString("description") != null &&
                                obj.getString("description").startsWith("Must be set to")
                ) {
                    obj.put("defaultValue", obj.getString("description").split("'")[1]);
                }
                if (
                        StringUtils.equals("jdbc", type) &&
                        StringUtils.equals("driver", obj.getString("key")) &&
                        StringUtils.equals("", obj.getString("defaultValue"))
                ){
                    obj.put("defaultValue", "org.mariadb.jdbc.Driver");
                    obj.put("required", true);
                }
                properties.add(obj);
            }
        }

        // 将含有#的配置按照columns拓展开
        if (propertiesWithSharp.size() > 0 && !StringUtils.isEmpty(columns)) {
            for (String column : columns.split(",")) {
                for (int i = 0; i < propertiesWithSharp.size(); i++) {
                    JSONObject property = propertiesWithSharp.getJSONObject(i);
                    JSONObject obj = property.clone();
                    String key = obj.getString("key").replace("#", column);
                    obj.put("key", key);
                    obj.put("label", key);
                    obj.put("value", key);
                    properties.add(obj);
                }
            }
        }

        return buildSucceedResult(JsonUtil.map(
                "options", properties,
                "dependencies", connector.getDependencies()
        ));
    }

}
