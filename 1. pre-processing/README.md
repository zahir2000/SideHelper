# Data Preprocessing - SideHelper

### Preparing the Dataset

[TensorFlow Lite Model Maker](https://www.tensorflow.org/lite/models/modify/model_maker) accepts a CSV format as explained [here](https://cloud.google.com/vision/automl/object-detection/docs/csv-format). So, I have created a [CreateDataset.ipynb](https://github.com/zahir2000/SideHelper/blob/main/preprocessing/CreateDataset.ipynb) to convert PascalVOC (from LabelImg) and OIDv4 data into the format above.

Moreover, the notebook includes steps to:
- Convert XML to CSV
- Convert Invalid Images to the Correct Format: sometimes the images are .jpg but actually they are not.
- Merge OIDv4 & PascalVOC