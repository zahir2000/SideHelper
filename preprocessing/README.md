# Data Preprocessing - SideHelper

### Preparing the Dataset

[https://www.tensorflow.org/lite/models/modify/model_maker](TensorFlow Lite Model Maker) accepts a CSV format as explained [https://cloud.google.com/vision/automl/object-detection/docs/csv-format](here). So, I have created a [https://github.com/github/codeql/blob/main/preprocessing/CreateDataset.ipynb](CreateDataset.ipynb) to convert PascalVOC (from LabelImg) and OIDv4 data into the format above.

Moreover, the notebook includes steps to:
- Convert XML to CSV
- Convert Invalid Images to the Correct Format: sometimes the images are .jpg but actually they are not.
- Merge OIDv4 & PascalVOC