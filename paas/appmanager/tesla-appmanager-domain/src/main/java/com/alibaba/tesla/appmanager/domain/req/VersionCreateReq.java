package com.alibaba.tesla.appmanager.domain.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VersionCreateReq {

    private String version;

    private String versionLabel;

}
