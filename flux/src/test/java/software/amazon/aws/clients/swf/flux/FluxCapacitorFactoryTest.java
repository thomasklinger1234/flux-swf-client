/*
 *   Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package software.amazon.aws.clients.swf.flux;

import org.junit.Assert;
import org.junit.Test;

import software.amazon.aws.clients.swf.flux.metrics.NoopMetricRecorderFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

public class FluxCapacitorFactoryTest {

    @Test
    public void testCreate() {
        AwsCredentialsProvider creds = () -> AwsBasicCredentials.create("access", "secret");

        FluxCapacitor fc = FluxCapacitorFactory.create(new NoopMetricRecorderFactory(), creds,
                                                       "us-east-1", "https://fake.example.com", "test");
        Assert.assertEquals(fc.getClass(), FluxCapacitorImpl.class);
    }
}
